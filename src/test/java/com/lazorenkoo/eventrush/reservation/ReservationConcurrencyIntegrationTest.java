package com.lazorenkoo.eventrush.reservation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
class ReservationConcurrencyIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationService service;

    private final UUID gate = UUID.randomUUID();
    private UUID seat;

    @BeforeEach
    void createSeatForAnOpenSale() {
        seat = jdbc.queryForObject("""
                with concert as (
                    insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id, status)
                    values ('Concurrent show', current_timestamp + interval '1 day', current_timestamp, ?, 'ON_SALE')
                    returning id
                )
                insert into seats (concert_id, section, seat_row, seat_number, price)
                select id, 'A', '1', 1, 10 from concert returning id
                """, UUID.class, gate);
    }

    @AfterEach
    void removeCommittedFixtures() {
        jdbc.update("delete from outgoing_events where payload::jsonb ->> 'seatId' = ?", seat.toString());
        jdbc.update("delete from reservations where seat_id = ?", seat);
        jdbc.update("delete from seats where id = ?", seat);
        jdbc.update("delete from concerts where crowdgate_gate_id = ?", gate);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 100})
    void concurrentBuyersCreateExactlyOneReservationAndEvent(int buyers) throws Exception {
        var ready = new CountDownLatch(buyers);
        var start = new CountDownLatch(1);
        var attempts = new ArrayList<Future<Integer>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < buyers; i++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent start timed out");
                    }
                    try {
                        service.reserve(seat, UUID.randomUUID(), gate);
                        return 1;
                    } catch (ResponseStatusException conflict) {
                        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
                        assertThat(conflict.getReason()).isEqualTo("SEAT_ALREADY_RESERVED");
                        return 0;
                    }
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            int winners = 0;
            for (var attempt : attempts) {
                winners += attempt.get(20, TimeUnit.SECONDS);
            }
            assertThat(winners).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from reservations where seat_id = ?",
                Integer.class, seat)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat))
                .isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("""
                select count(*) from outgoing_events
                where event_type = 'ticket.reserved' and payload::jsonb ->> 'seatId' = ?
                """, Integer.class, seat.toString())).isEqualTo(1);
    }
}
