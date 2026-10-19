package com.lazorenkoo.eventrush.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class ReservationCancellationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private ReservationCancellationService cancellation;

    private final UUID gate = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private UUID seat;
    private UUID reservation;

    @BeforeEach
    void createActiveReservation() {
        seat = jdbc.queryForObject("""
                with concert as (
                    insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id, status)
                    values ('Cancellation show', current_timestamp + interval '1 day', current_timestamp, ?, 'ON_SALE')
                    returning id
                )
                insert into seats (concert_id, section, seat_row, seat_number, price)
                select id, 'A', '1', 1, 10 from concert returning id
                """, UUID.class, gate);
        reservation = reservations.reserve(seat, owner, gate).id();
    }

    @Test
    void anotherUserCannotCancelTheReservation() {
        assertThatThrownBy(() -> cancellation.cancel(reservation, UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat))
                .isEqualTo("RESERVED");
    }

    @Test
    void repeatedCancellationCannotReleaseARebookedSeat() {
        jdbc.update("insert into payments (reservation_id, amount, simulation_mode) values (?, 10, 'DELAYED_SUCCESS')",
                reservation);
        cancellation.cancel(reservation, owner);
        assertThat(jdbc.queryForObject("select status from reservations where id = ?", String.class, reservation))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat))
                .isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select status from payments where reservation_id = ?", String.class, reservation))
                .isEqualTo("FAILED");
        UUID next = reservations.reserve(seat, UUID.randomUUID(), gate).id();
        cancellation.cancel(reservation, owner);
        assertThat(next).isNotEqualTo(reservation);
        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat))
                .isEqualTo("RESERVED");
    }

    @Test
    void confirmedPurchaseCannotBeCancelled() {
        jdbc.update("update reservations set status = 'CONFIRMED' where id = ?", reservation);
        jdbc.update("update seats set status = 'SOLD' where id = ?", seat);
        assertThatThrownBy(() -> cancellation.cancel(reservation, owner))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat))
                .isEqualTo("SOLD");
    }

    @Test
    void expiredActiveReservationCannotBeCancelled() {
        jdbc.update("""
                update reservations set created_at = clock_timestamp() - interval '2 minutes',
                    expires_at = clock_timestamp() - interval '1 minute' where id = ?
                """, reservation);
        assertThatThrownBy(() -> cancellation.cancel(reservation, owner))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThat(jdbc.queryForObject("select status from seats where id = ?", String.class, seat))
                .isEqualTo("RESERVED");
    }
}
