package com.lazorenkoo.eventrush.load;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///load_leases_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
class LoadClientLeasesIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoadRunImportService imports;
    @Autowired private LoadRunLifecycleService lifecycle;
    @Autowired private LoadClientLeases leases;
    private UUID concert;
    private UUID run;

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id,status)
                values ('Lease tests',now()+interval '1 day',now(),?,'ON_SALE') returning id
                """, UUID.class, UUID.randomUUID());
        jdbc.update("insert into seats(concert_id,section,seat_row,seat_number,price) values(?,'A','1',300,25)", concert);
        byte[] csv = ("firstName,lastName,seatNumber\n" + "First,Last,300\n".repeat(10))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        run = imports.importCsv(concert, 3, new ByteArrayInputStream(csv)).id();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from load_test_clients where run_id=?", run);
        jdbc.update("delete from load_test_runs where id=?", run);
        jdbc.update("delete from seats where concert_id=?", concert);
        jdbc.update("delete from concerts where id=?", concert);
    }

    @Test
    void concurrentClaimsNeverDuplicateClientsOrExceedCapacity() throws Exception {
        lifecycle.start(concert, run);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<java.util.List<LoadClientLeases.Client>>>();
            for (int index = 0; index < 8; index++) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out");
                return leases.claim(run, 1);
            }));
            try {
                assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            var claimed = new ArrayList<UUID>();
            for (var future : futures) for (var client : future.get(5, TimeUnit.SECONDS)) claimed.add(client.id());
            assertThat(claimed).hasSize(3).doesNotHaveDuplicates();
            assertThat(leases.claim(run)).isEmpty();
        }
    }

    @Test
    void localCapacityBoundsClaimsWithoutOverridingRunConcurrency() {
        lifecycle.start(concert, run);
        var first = leases.claim(run, 1);
        var second = leases.claim(run, 1);
        var last = leases.claim(run, 100);
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(last).hasSize(1);
        var ids = new ArrayList<UUID>();
        for (var batch : java.util.List.of(first, second, last)) {
            for (var client : batch) ids.add(client.id());
        }
        assertThat(ids).hasSize(3).doesNotHaveDuplicates();
        assertThat(leases.claim(run, 1)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from load_test_clients where run_id=? and status='RUNNING'",
                Integer.class, run)).isEqualTo(3);
        lifecycle.stop(concert, run);
        assertThat(leases.claim(run, 100)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101, Integer.MAX_VALUE})
    void invalidLocalCapacityCannotClaimAnyClients(int capacity) {
        lifecycle.start(concert, run);
        assertThatThrownBy(() -> leases.claim(run, capacity)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from load_test_clients where run_id=? and status='PENDING'",
                Integer.class, run)).isEqualTo(10);
    }

    @Test
    void renewalRequiresUnexpiredTokenAndExpiredClientIsNeverReplayed() {
        lifecycle.start(concert, run);
        var old = leases.claim(run).getFirst();
        assertThat(old.toString()).doesNotContain("First", old.leaseToken().toString());
        assertThat(leases.renew(old.id(), UUID.randomUUID())).isFalse();
        assertThat(leases.renew(old.id(), old.leaseToken())).isTrue();
        jdbc.update("update load_test_clients set lease_expires_at=clock_timestamp()-interval '1 second' where id=?", old.id());
        assertThat(leases.renew(old.id(), old.leaseToken())).isFalse();
        assertThat(leases.claim(run)).hasSize(1).extracting(LoadClientLeases.Client::id).doesNotContain(old.id());
        assertThat(jdbc.queryForObject("select error_code from load_test_clients where id=?", String.class, old.id()))
                .isEqualTo("WORKER_LEASE_EXPIRED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reaperWaitsForLiveClientsAndFinalizesOnlyAfterExpiry(boolean stop) {
        jdbc.update("update load_test_runs set concurrency=10 where id=?", run);
        lifecycle.start(concert, run);
        assertThat(leases.claim(run)).hasSize(10);
        if (stop) lifecycle.stop(concert, run);
        leases.reap(run);
        assertThat(jdbc.queryForObject("select status from load_test_runs where id=?", String.class, run))
                .isEqualTo(stop ? "STOPPING" : "RUNNING");
        jdbc.update("update load_test_clients set lease_expires_at=clock_timestamp()-interval '1 second' where run_id=?", run);
        leases.reap(run);
        leases.reap(run);
        assertThat(jdbc.queryForObject("select status from load_test_runs where id=?", String.class, run))
                .isEqualTo(stop ? "STOPPED" : "COMPLETED");
        assertThat(leases.claim(run)).isEmpty();
    }
}
