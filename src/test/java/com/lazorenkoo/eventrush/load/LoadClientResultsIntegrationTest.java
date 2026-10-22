package com.lazorenkoo.eventrush.load;

import com.lazorenkoo.eventrush.purchase.ParticipantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///client_results_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
class LoadClientResultsIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoadRunImportService imports;
    @Autowired private LoadRunLifecycleService lifecycle;
    @Autowired private LoadClientLeases leases;
    @Autowired private LoadClientResults results;
    @Autowired private ParticipantService participants;
    private UUID concert;
    private UUID run;
    private UUID participant;
    private LoadClientLeases.Client client;

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id,status)
                values ('Client result',now()+interval '1 day',now(),?,'ON_SALE') returning id
                """, UUID.class, UUID.randomUUID());
        jdbc.update("insert into seats(concert_id,section,seat_row,seat_number,price) values(?,'A','1',300,25)", concert);
        run = imports.importCsv(concert, 1, new ByteArrayInputStream(
                "firstName,lastName,seatNumber\nFirst,Last,300".getBytes(java.nio.charset.StandardCharsets.UTF_8))).id();
        lifecycle.start(concert, run);
        client = leases.claim(run).getFirst();
        participant = participants.createTest(client.id(), concert, client.seatId(), "First", "Last", run);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from load_test_clients where run_id=?", run);
        jdbc.update("delete from purchase_participants where concert_id=?", concert);
        jdbc.update("delete from load_test_runs where id=?", run);
        jdbc.update("delete from seats where concert_id=?", concert);
        jdbc.update("delete from concerts where id=?", concert);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wrongOrExpiredTokenCannotLinkOrFinish(boolean expired) {
        if (expired) jdbc.update("update load_test_clients set lease_expires_at=clock_timestamp()-interval '1 second' where id=?", client.id());
        UUID token = expired ? client.leaseToken() : UUID.randomUUID();
        assertThat(results.link(client.id(), token, participant)).isFalse();
        assertThat(results.finish(client.id(), token, LoadClientResults.Outcome.CANCELLED, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_id=gen_random_uuid()", "run_id=gen_random_uuid()", "first_name='Other'",
            "source='MANUAL',run_id=null"})
    void rejectsParticipantIdentityMismatch(String assignment) {
        jdbc.update("update purchase_participants set " + assignment + " where id=?", participant);
        assertThat(results.link(client.id(), client.leaseToken(), participant)).isFalse();
    }

    @Test
    void matchingLinkIsIdempotentButDoesNotProvePurchase() {
        assertThat(results.link(client.id(), client.leaseToken(), participant)).isTrue();
        assertThat(results.link(client.id(), client.leaseToken(), participant)).isTrue();
        assertThat(results.finish(client.id(), client.leaseToken(), LoadClientResults.Outcome.SUCCEEDED, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "SOLD_OUT", "CANCELLED"})
    void terminalOutcomeClearsLeaseAndCannotBeOverwritten(String state) {
        var outcome = LoadClientResults.Outcome.valueOf(state);
        var failure = outcome == LoadClientResults.Outcome.FAILED ? LoadClientResults.Failure.HTTP_ERROR : null;
        assertThat(results.finish(client.id(), client.leaseToken(), outcome, failure)).isTrue();
        assertThat(results.finish(client.id(), client.leaseToken(), outcome, failure)).isFalse();
        assertThat(leases.renew(client.id(), client.leaseToken())).isFalse();
        assertThat(jdbc.queryForObject("select status from load_test_clients where id=?", String.class, client.id()))
                .isEqualTo(state);
    }

    @Test
    void concurrentCompletionHasExactlyOneWinner() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> finish = () -> {
            ready.countDown();
            if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out");
            return results.finish(client.id(), client.leaseToken(), LoadClientResults.Outcome.CANCELLED, null);
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(finish);
            var b = pool.submit(finish);
            try {
                assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat((a.get(5, TimeUnit.SECONDS) ? 1 : 0) + (b.get(5, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
    }
}
