package com.lazorenkoo.eventrush.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
class SaleOpeningIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SaleOpeningService opening;
    @Autowired
    private SeatCreationService seats;
    @Autowired
    private PlatformTransactionManager transactions;

    private UUID concert;

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id)
                values ('Opening show', now() + interval '1 day', now() + interval '1 hour', ?) returning id
                """, UUID.class, UUID.randomUUID());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from seats where concert_id = ?", concert);
        jdbc.update("delete from concerts where id = ?", concert);
    }

    @Test
    void requiresInventoryAndPreservesScheduleOnIdempotentOpening() {
        assertThatThrownBy(() -> opening.open(concert)).isInstanceOf(ResponseStatusException.class);
        seats.create(concert, List.of(seat(1)));
        var schedule = jdbc.queryForObject("select sale_starts_at from concerts where id = ?",
                java.sql.Timestamp.class, concert);
        opening.open(concert);
        opening.open(concert);
        assertThat(jdbc.queryForObject("select status from concerts where id = ?", String.class, concert))
                .isEqualTo("ON_SALE");
        assertThat(jdbc.queryForObject("select sale_starts_at from concerts where id = ?",
                java.sql.Timestamp.class, concert)).isEqualTo(schedule);
    }

    @Test
    void concurrentSeatInsertionWaitsForOpeningThenRejectsWithoutChangingInventory() throws Exception {
        seats.create(concert, List.of(seat(1)));
        var workerPid = new AtomicInteger();
        var started = new CountDownLatch(1);
        var transaction = new TransactionTemplate(transactions);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = transaction.execute(status -> {
                opening.open(concert);
                int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                var future = executor.submit(() -> {
                    try {
                        transaction.executeWithoutResult(worker -> {
                            workerPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                            started.countDown();
                            seats.create(concert, List.of(seat(2)));
                        });
                        return 201;
                    } catch (ResponseStatusException error) {
                        return error.getStatusCode().value();
                    }
                });
                try {
                    assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    boolean blocked;
                    do {
                        blocked = jdbc.queryForObject("select ? = any(pg_blocking_pids(?))", Boolean.class,
                                blocker, workerPid.get());
                    } while (!blocked && System.nanoTime() < deadline);
                    assertThat(blocked).isTrue();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                return future;
            });
            assertThat(pending.get(3, TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("select count(*) from seats where concert_id = ?", Integer.class, concert))
                .isEqualTo(1);
    }

    private SeatCreationService.SeatDefinition seat(int number) {
        return new SeatCreationService.SeatDefinition("A", "1", number, BigDecimal.TEN);
    }
}
