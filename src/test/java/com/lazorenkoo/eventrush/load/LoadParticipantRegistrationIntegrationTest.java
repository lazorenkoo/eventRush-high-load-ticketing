package com.lazorenkoo.eventrush.load;

import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient;
import com.lazorenkoo.eventrush.purchase.ParticipantActivity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///load_registration_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "eventrush.crowdgate.enabled=true", "eventrush.crowdgate.base-url=http://localhost:8080"
})
class LoadParticipantRegistrationIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoadRunImportService imports;
    @Autowired private LoadRunLifecycleService lifecycle;
    @Autowired private LoadClientLeases leases;
    @Autowired private LoadParticipantRegistration registration;
    @MockitoBean private CrowdGateClient crowdgate;
    @MockitoSpyBean private ParticipantActivity journal;
    private UUID concert;
    private UUID run;
    private UUID gate;
    private String token;
    private LoadClientLeases.Client client;

    @BeforeEach
    void setup() {
        gate = UUID.randomUUID();
        token = gate + ":" + UUID.randomUUID();
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id,status)
                values ('Registration',now()+interval '1 day',now(),?,'ON_SALE') returning id
                """, UUID.class, gate);
        jdbc.update("insert into seats(concert_id,section,seat_row,seat_number,price) values(?,'A','1',300,25)", concert);
        run = imports.importCsv(concert, 1, new ByteArrayInputStream(
                "firstName,lastName,seatNumber\nFirst,Last,300".getBytes(java.nio.charset.StandardCharsets.UTF_8))).id();
        lifecycle.start(concert, run);
        client = leases.claim(run).getFirst();
        when(crowdgate.requireQueueOwner(eq(gate), eq(client.id()), anyString())).thenReturn("WAITING");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from load_test_clients where run_id=?", run);
        jdbc.update("delete from participant_activity where participant_id in "
                + "(select id from purchase_participants where concert_id=?)", concert);
        jdbc.update("delete from purchase_participants where concert_id=?", concert);
        jdbc.update("delete from load_test_runs where id=?", run);
        jdbc.update("delete from seats where concert_id=?", concert);
        jdbc.update("delete from concerts where id=?", concert);
    }

    @Test
    void concurrentReplayCreatesOneTestParticipantAndTwoJournalEntries() throws Exception {
        var barrier = new CyclicBarrier(2);
        java.util.concurrent.Callable<UUID> register = () -> {
            barrier.await(3, TimeUnit.SECONDS);
            return registration.register(client.id(), client.leaseToken(), token);
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(register);
            var b = pool.submit(register);
            UUID participant = a.get(5, TimeUnit.SECONDS);
            assertThat(b.get(5, TimeUnit.SECONDS)).isEqualTo(participant);
            assertThat(jdbc.queryForObject("select source from purchase_participants where id=?", String.class, participant))
                    .isEqualTo("TEST");
            assertThat(jdbc.queryForObject("select count(*) from participant_activity where participant_id=?",
                    Integer.class, participant)).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEASE", "STOP", "OWNER", "QUEUE"})
    void invalidContextCannotCreateAnotherParticipant(String mode) {
        if (mode.equals("LEASE")) jdbc.update("update load_test_clients set lease_expires_at=clock_timestamp()-interval '1 second' where id=?", client.id());
        if (mode.equals("STOP")) lifecycle.stop(concert, run);
        if (mode.equals("OWNER")) when(crowdgate.requireQueueOwner(gate, client.id(), token))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "CROWDGATE_QUEUE_REJECTED"));
        if (mode.equals("QUEUE")) registration.register(client.id(), client.leaseToken(), token);
        String next = mode.equals("QUEUE") ? gate + ":" + UUID.randomUUID() : token;
        assertThatThrownBy(() -> registration.register(client.id(), client.leaseToken(), next))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("select count(*) from purchase_participants where concert_id=?",
                Integer.class, concert)).isEqualTo(mode.equals("QUEUE") ? 1 : 0);
    }

    @Test
    void journalFailureRollsBackParticipantAndClientLink() {
        ParticipantActivity target = AopTestUtils.getUltimateTargetObject(journal);
        doThrow(new IllegalStateException("Journal failure")).when(target)
                .append(any(), any(), eq(ParticipantActivity.Kind.REGISTRATION), eq("REGISTERED"));
        assertThatThrownBy(() -> registration.register(client.id(), client.leaseToken(), token))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select participant_id from load_test_clients where id=?", UUID.class, client.id())).isNull();
        assertThat(jdbc.queryForObject("select count(*) from purchase_participants where concert_id=?", Integer.class, concert)).isZero();
    }
}
