package com.lazorenkoo.eventrush.load;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///load_lifecycle_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
class LoadRunLifecycleIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoadRunImportService imports;
    @Autowired private LoadRunLifecycleService lifecycle;
    private UUID concert;
    private UUID first;
    private UUID second;

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id,status)
                values ('Lifecycle',now()+interval '1 day',now(),?,'ON_SALE') returning id
                """, UUID.class, UUID.randomUUID());
        jdbc.update("insert into seats(concert_id,section,seat_row,seat_number,price) values(?,'A','1',300,25)", concert);
        byte[] csv = "firstName,lastName,seatNumber\nFirst,Last,300".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        first = imports.importCsv(concert, 20, new ByteArrayInputStream(csv)).id();
        second = imports.importCsv(concert, 20, new ByteArrayInputStream(csv)).id();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from load_test_clients where concert_id=?", concert);
        jdbc.update("delete from load_test_runs where concert_id=?", concert);
        jdbc.update("delete from seats where concert_id=?", concert);
        jdbc.update("delete from concerts where id=?", concert);
    }

    private boolean start(UUID run, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("Start barrier timed out");
        try {
            return lifecycle.start(concert, run).equals("RUNNING");
        } catch (ResponseStatusException exception) {
            assertThat(exception.getReason()).isEqualTo("LOAD_RUN_ALREADY_ACTIVE");
            return false;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentStartsAreIdempotentAndAllowOnlyOneActiveRun(boolean sameRun) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> start(first, ready, start));
            var b = pool.submit(() -> start(sameRun ? first : second, ready, start));
            try {
                assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            int successful = (a.get(5, TimeUnit.SECONDS) ? 1 : 0) + (b.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(successful).isEqualTo(sameRun ? 2 : 1);
        }
        UUID active = jdbc.queryForObject("select id from load_test_runs where status='RUNNING'", UUID.class);
        assertThat(lifecycle.stop(concert, active)).isEqualTo("STOPPING");
        assertThat(lifecycle.stop(concert, active)).isEqualTo("STOPPING");
        UUID other = active.equals(first) ? second : first;
        assertThatThrownBy(() -> lifecycle.start(concert, other)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void stoppingReadyRunCancelsClientsAndCannotRestart() {
        assertThat(lifecycle.stop(concert, first)).isEqualTo("STOPPED");
        assertThat(lifecycle.stop(concert, first)).isEqualTo("STOPPED");
        assertThat(jdbc.queryForObject("select status from load_test_clients where run_id=?", String.class, first))
                .isEqualTo("CANCELLED");
        assertThatThrownBy(() -> lifecycle.start(concert, first)).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSED", "FUTURE", "INCOMPLETE", "FOREIGN"})
    void invalidStartLeavesReadyRunUnchanged(String mode) {
        if (mode.equals("CLOSED")) jdbc.update("update concerts set status='CANCELLED' where id=?", concert);
        if (mode.equals("FUTURE")) jdbc.update("update concerts set sale_starts_at=now()+interval '1 hour' where id=?", concert);
        if (mode.equals("INCOMPLETE")) jdbc.update("delete from load_test_clients where run_id=?", first);
        UUID target = mode.equals("FOREIGN") ? UUID.randomUUID() : concert;
        assertThatThrownBy(() -> lifecycle.start(target, first)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("select status from load_test_runs where id=?", String.class, first))
                .isEqualTo("READY");
    }
}
