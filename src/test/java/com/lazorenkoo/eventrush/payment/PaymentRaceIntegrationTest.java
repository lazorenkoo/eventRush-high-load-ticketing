package com.lazorenkoo.eventrush.payment;

import com.lazorenkoo.eventrush.reservation.ReservationExpirationService;
import com.lazorenkoo.eventrush.reservation.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_payment_race_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "eventrush.expiration.enabled=false"
})
class PaymentRaceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private PaymentService payments;

    @Autowired
    private PaymentCompletionService completion;

    @Autowired
    private ReservationExpirationService expiry;

    private final UUID gate = UUID.randomUUID();
    private UUID seat;
    private UUID reservation;

    @BeforeEach
    void createPendingPayment() {
        seat = jdbc.queryForObject("""
                with concert as (
                    insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id, status)
                    values ('Payment race', current_timestamp + interval '1 day', current_timestamp, ?, 'ON_SALE')
                    returning id
                )
                insert into seats (concert_id, section, seat_row, seat_number, price)
                select id, 'A', '1', 1, 10 from concert returning id
                """, UUID.class, gate);
        UUID owner = UUID.randomUUID();
        reservation = reservations.reserve(seat, owner, gate).id();
        payments.start(reservation, owner, PaymentService.Mode.SUCCESS);
    }

    @AfterEach
    void removeCommittedFixtures() {
        jdbc.update("delete from payments where reservation_id = ?", reservation);
        jdbc.update("delete from outgoing_events where payload::jsonb ->> 'seatId' = ?", seat.toString());
        jdbc.update("delete from reservations where seat_id = ?", seat);
        jdbc.update("delete from seats where id = ?", seat);
        jdbc.update("delete from concerts where crowdgate_gate_id = ?", gate);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentCompletionAndExpiryPreserveOneTerminalOutcome(boolean expired) throws Exception {
        if (expired) {
            jdbc.update("""
                    update reservations set created_at = clock_timestamp() - interval '2 minutes',
                        expires_at = clock_timestamp() - interval '1 minute' where id = ?
                    """, reservation);
        }
        var start = new CountDownLatch(1);
        String expected = expired ? "FAILED" : "SUCCESS";
        try (var executor = Executors.newFixedThreadPool(3)) {
            var first = executor.submit(() -> {
                start.await();
                return completion.complete(reservation);
            });
            var second = executor.submit(() -> {
                start.await();
                return completion.complete(reservation);
            });
            var expiration = executor.submit(() -> {
                start.await();
                return expiry.expireBatch();
            });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(expiration.get(15, TimeUnit.SECONDS)).isBetween(0, 1);
        }
        assertThat(jdbc.queryForObject("select status from reservations where id = ?", String.class, reservation))
                .isEqualTo(expired ? "EXPIRED" : "CONFIRMED");
        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat))
                .isEqualTo(expired ? "AVAILABLE" : "SOLD");
        assertThat(jdbc.queryForObject("select status from payments where reservation_id = ?", String.class, reservation))
                .isEqualTo(expected);
        assertThat(jdbc.queryForObject("""
                select count(*) from outgoing_events where payload::jsonb ->> 'reservationId' = ?
                    and event_type in ('ticket.purchased', 'reservation.expired')
                """, Integer.class, reservation.toString())).isEqualTo(1);
    }
}
