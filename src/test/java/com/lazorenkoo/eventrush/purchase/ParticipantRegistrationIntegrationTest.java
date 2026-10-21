package com.lazorenkoo.eventrush.purchase;

import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ParticipantRegistrationIntegrationTest {
    private static JdbcTemplate jdbc;
    private static Connection keepAlive;
    private CrowdGateClient crowdgate;
    private ParticipantRegistrationService service;
    private UUID visitor;
    private UUID concert;
    private UUID seat;
    private UUID gate;
    private String token;

    @BeforeAll
    static void database() throws Exception {
        var data = new DriverManagerDataSource("jdbc:tc:postgresql:17.11:///registration_test", "test", "test");
        data.setDriverClassName("org.testcontainers.jdbc.ContainerDatabaseDriver");
        keepAlive = data.getConnection();
        Flyway.configure().dataSource(data).load().migrate();
        jdbc = new JdbcTemplate(data);
    }

    @AfterAll
    static void close() throws Exception { keepAlive.close(); }

    @BeforeEach
    void setup() {
        visitor = UUID.randomUUID();
        gate = UUID.randomUUID();
        token = gate + ":" + UUID.randomUUID();
        crowdgate = mock(CrowdGateClient.class);
        service = new ParticipantRegistrationService(jdbc, crowdgate, mock(ParticipantActivity.class));
        when(crowdgate.requireQueueOwner(gate, visitor, token)).thenReturn("WAITING");
        concert = jdbc.queryForObject("""
                insert into concerts(name, starts_at, sale_starts_at, crowdgate_gate_id, status)
                values ('Registration test',now()+interval '1 day',now(),?,'ON_SALE') returning id
                """, UUID.class, gate);
        seat = jdbc.queryForObject("""
                insert into seats(concert_id,section,seat_row,seat_number,price)
                values (?,'A','1',1,25) returning id
                """, UUID.class, concert);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from purchase_participants where concert_id=?", concert);
        jdbc.update("delete from seats where concert_id=?", concert);
        jdbc.update("delete from concerts where id=?", concert);
    }

    private UUID register(String name) { return service.register(visitor, concert, seat, token, name, "Last"); }

    @Test
    void concurrentReplayCreatesOneVerifiedParticipant() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var barrier = new CyclicBarrier(8);
            var calls = IntStream.range(0, 16).mapToObj(i -> (Callable<UUID>) () -> {
                barrier.await();
                return register("First");
            }).toList();
            var results = pool.invokeAll(calls);
            UUID expected = results.getFirst().get();
            for (var result : results) assertThat(result.get()).isEqualTo(expected);
        }
        assertThat(jdbc.queryForObject("select count(*) from purchase_participants where concert_id=?",
                Integer.class, concert)).isEqualTo(1);
        verify(crowdgate, times(16)).requireQueueOwner(gate, visitor, token);
        String stored = jdbc.queryForObject("select queue_key from purchase_participants where concert_id=?",
                String.class, concert);
        assertThat(stored).matches("[0-9a-f]{64}").doesNotContain(token);
    }

    @Test
    void changedPayloadConflictsWithoutOverwritingNames() {
        UUID id = register("First");
        assertThatThrownBy(() -> register("Changed")).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThat(jdbc.queryForObject("select first_name from purchase_participants where id=?",
                String.class, id)).isEqualTo("First");
    }

    @Test
    void rejectedQueueOwnershipCreatesNoRow() {
        when(crowdgate.requireQueueOwner(gate, visitor, token))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> register("First")).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("select count(*) from purchase_participants where concert_id=?",
                Integer.class, concert)).isZero();
    }

    @Test
    void mismatchedConcertSeatNeverCallsCrowdGate() {
        assertThatThrownBy(() -> service.register(visitor, UUID.randomUUID(), seat, token, "First", "Last"))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(crowdgate);
    }
}
