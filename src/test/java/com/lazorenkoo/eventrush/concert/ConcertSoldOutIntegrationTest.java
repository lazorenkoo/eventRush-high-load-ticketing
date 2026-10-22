package com.lazorenkoo.eventrush.concert;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_sold_out_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "eventrush.expiration.enabled=false", "eventrush.concert.sold-out-worker.enabled=false"
})
class ConcertSoldOutIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ConcertSoldOutService service;
    @Autowired
    private PlatformTransactionManager transactions;

    private final List<UUID> concerts = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        for (UUID id : concerts) {
            jdbc.update("delete from outgoing_events where payload::jsonb ->> 'concertId' = ?", id.toString());
            jdbc.update("delete from seats where concert_id = ?", id);
            jdbc.update("delete from concerts where id = ?", id);
        }
    }

    @Test
    void onlyNonemptyFullySoldConcertTransitionsAndEmitsOnce() {
        UUID sold = concert("ON_SALE", "SOLD");
        UUID empty = concert("ON_SALE");
        UUID partial = concert("ON_SALE", "SOLD", "RESERVED", "AVAILABLE");
        UUID draft = concert("DRAFT", "SOLD");
        assertThat(service.markSoldOutBatch()).isEqualTo(1);
        assertThat(service.markSoldOutBatch()).isZero();
        assertThat(status(sold)).isEqualTo("SOLD_OUT");
        assertThat(status(empty)).isEqualTo("ON_SALE");
        assertThat(status(partial)).isEqualTo("ON_SALE");
        assertThat(status(draft)).isEqualTo("DRAFT");
        assertThat(eventCount(sold)).isEqualTo(1);
    }

    @Test
    void rollbackUndoesStatusAndEventTogether() {
        UUID id = concert("ON_SALE", "SOLD");
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            assertThat(service.markSoldOutBatch()).isEqualTo(1);
            assertThat(eventCount(id)).isEqualTo(1);
            transaction.setRollbackOnly();
        });
        assertThat(status(id)).isEqualTo("ON_SALE");
        assertThat(eventCount(id)).isZero();
        assertThat(service.markSoldOutBatch()).isEqualTo(1);
    }

    @Test
    void anotherWorkerSkipsLockedConcertAndProcessesItAfterRelease() {
        UUID id = concert("ON_SALE", "SOLD");
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            jdbc.queryForObject("select id from concerts where id = ? for update", UUID.class, id);
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(service::markSoldOutBatch).get(3, TimeUnit.SECONDS)).isZero();
            } catch (Exception error) {
                throw new AssertionError("Sold-out worker blocked or failed", error);
            }
        });
        assertThat(service.markSoldOutBatch()).isEqualTo(1);
        assertThat(eventCount(id)).isEqualTo(1);
    }

    private UUID concert(String status, String... seats) {
        UUID id = UUID.randomUUID();
        concerts.add(id);
        jdbc.update("""
                insert into concerts (id, name, starts_at, sale_starts_at, status, crowdgate_gate_id)
                values (?, 'Show', now() + interval '1 day', now(), ?, ?)
                """, id, status, UUID.randomUUID());
        for (int i = 0; i < seats.length; i++) {
            jdbc.update("""
                    insert into seats (concert_id, section, seat_row, seat_number, price, status)
                    values (?, 'A', '1', ?, 10, ?)
                    """, id, i + 1, seats[i]);
        }
        return id;
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from concerts where id = ?", String.class, id);
    }

    private int eventCount(UUID id) {
        return jdbc.queryForObject("""
                select count(*) from outgoing_events
                where event_type = 'concert.sold_out' and payload::jsonb ->> 'concertId' = ?
                """, Integer.class, id.toString());
    }
}
