package com.lazorenkoo.eventrush.payment;

import com.lazorenkoo.eventrush.reservation.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
@Transactional
class PaymentCompletionIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private PaymentService payments;

    @Autowired
    private PaymentCompletionService completion;

    private final UUID gate = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private UUID seat;
    private UUID reservation;

    @BeforeEach
    void createActiveReservation() {
        seat = jdbc.queryForObject("""
                with concert as (
                    insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id, status)
                    values ('Payment show', current_timestamp + interval '1 day', current_timestamp, ?, 'ON_SALE')
                    returning id
                )
                insert into seats (concert_id, section, seat_row, seat_number, price)
                select id, 'A', '1', 1, 10 from concert returning id
                """, UUID.class, gate);
        reservation = reservations.reserve(seat, owner, gate).id();
    }

    @ParameterizedTest
    @EnumSource(PaymentService.Mode.class)
    void completionAppliesOneOutcomeAndOneEvent(PaymentService.Mode mode) {
        payments.start(reservation, owner, mode);
        if (mode == PaymentService.Mode.DELAYED_SUCCESS) {
            assertThat(completion.complete(reservation)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat))
                    .isEqualTo("RESERVED");
            jdbc.update("update payments set complete_after = clock_timestamp() - interval '1 second' where reservation_id = ?",
                    reservation);
        }
        boolean success = mode != PaymentService.Mode.FAILED;
        String result = success ? "SUCCESS" : "FAILED";
        assertThat(completion.complete(reservation)).isEqualTo(result);
        assertThat(completion.complete(reservation)).isEqualTo(result);
        assertThat(jdbc.queryForObject("select status from reservations where id = ?", String.class, reservation))
                .isEqualTo(success ? "CONFIRMED" : "CANCELLED");
        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat))
                .isEqualTo(success ? "SOLD" : "AVAILABLE");
        assertThat(jdbc.queryForObject("select status from payments where reservation_id = ?", String.class, reservation))
                .isEqualTo(result);
        assertThat(jdbc.queryForObject("""
                select count(*) from outgoing_events
                where event_type = ? and payload::jsonb ->> 'reservationId' = ?
                """, Integer.class, success ? "ticket.purchased" : "payment.failed", reservation.toString()))
                .isEqualTo(1);
        if (success) {
            assertThatThrownBy(() -> reservations.reserve(seat, UUID.randomUUID(), gate))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        }
    }

    @Test
    void expiryBeforeCompletionPreventsPurchase() {
        payments.start(reservation, owner, PaymentService.Mode.SUCCESS);
        jdbc.update("""
                update reservations set created_at = clock_timestamp() - interval '2 minutes',
                    expires_at = clock_timestamp() - interval '1 minute' where id = ?
                """, reservation);
        assertThat(completion.complete(reservation)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select status from reservations where id = ?", String.class, reservation))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat))
                .isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("""
                select count(*) from outgoing_events
                where event_type = 'reservation.expired' and payload::jsonb ->> 'reservationId' = ?
                """, Integer.class, reservation.toString())).isEqualTo(1);
    }
}
