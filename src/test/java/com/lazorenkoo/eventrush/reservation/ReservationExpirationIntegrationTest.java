package com.lazorenkoo.eventrush.reservation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_expiry_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "eventrush.expiration.enabled=false"
})
class ReservationExpirationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private ReservationExpirationService expiry;

    @Autowired
    private PlatformTransactionManager transactions;

    private final UUID gate = UUID.randomUUID();
    private UUID seat;
    private UUID reservation;

    @BeforeEach
    void createExpiredReservation() {
        seat = jdbc.queryForObject("""
                with concert as (
                    insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id, status)
                    values ('Expiry show', current_timestamp + interval '1 day', current_timestamp, ?, 'ON_SALE')
                    returning id
                )
                insert into seats (concert_id, section, seat_row, seat_number, price)
                select id, 'A', '1', 1, 10 from concert returning id
                """, UUID.class, gate);
        reservation = reservations.reserve(seat, UUID.randomUUID(), gate).id();
        jdbc.update("""
                update reservations set created_at = clock_timestamp() - interval '2 minutes',
                    expires_at = clock_timestamp() - interval '1 minute' where id = ?
                """, reservation);
    }

    @AfterEach
    void removeCommittedFixtures() {
        jdbc.update("delete from payments where reservation_id = ?", reservation);
        jdbc.update("delete from outgoing_events where payload::jsonb ->> 'seatId' = ?", seat.toString());
        jdbc.update("delete from reservations where seat_id = ?", seat);
        jdbc.update("delete from seats where id = ?", seat);
        jdbc.update("delete from concerts where crowdgate_gate_id = ?", gate);
    }

    @Test
    void expiryClosesPaymentEmitsOneEventAndPermitsRebooking() {
        jdbc.update("insert into payments (reservation_id, amount, simulation_mode) values (?, 10, 'DELAYED_SUCCESS')",
                reservation);
        assertThat(expiry.expireBatch()).isEqualTo(1);
        assertThat(expiry.expireBatch()).isZero();
        assertThat(jdbc.queryForObject("select status from reservations where id = ?", String.class, reservation))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat))
                .isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select status from payments where reservation_id = ?", String.class, reservation))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                select count(*) from outgoing_events
                where event_type = 'reservation.expired' and payload::jsonb ->> 'reservationId' = ?
                """, Integer.class, reservation.toString())).isEqualTo(1);
        assertThat(reservations.reserve(seat, UUID.randomUUID(), gate).id()).isNotEqualTo(reservation);
    }

    @Test
    void anotherWorkerSkipsLockedReservationAndProcessesItAfterRelease() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.queryForObject("select id from reservations where id = ? for update", UUID.class, reservation);
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(expiry::expireBatch).get(3, TimeUnit.SECONDS)).isZero();
            } catch (Exception error) {
                throw new AssertionError("Worker blocked or failed on a locked reservation", error);
            }
        });
        assertThat(expiry.expireBatch()).isEqualTo(1);
        assertThat(expiry.expireBatch()).isZero();
    }
}
