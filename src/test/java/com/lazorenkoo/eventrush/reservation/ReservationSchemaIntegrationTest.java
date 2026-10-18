package com.lazorenkoo.eventrush.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

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
class ReservationSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private UUID seat;

    @BeforeEach
    void createSeat() {
        seat = jdbc.queryForObject("""
                with concert as (
                    insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id)
                    values ('Live show', current_timestamp + interval '1 day', current_timestamp, ?)
                    returning id
                )
                insert into seats (concert_id, section, seat_row, seat_number, price)
                select id, 'A', '1', 1, 10 from concert returning id
                """, UUID.class, UUID.randomUUID());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "CONFIRMED"})
    void ownedSeatRejectsAnotherReservation(String status) {
        reservation(status);
        assertThatThrownBy(() -> reservation("ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("reservations_one_owner_per_seat");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "EXPIRED"})
    void releasedReservationHistoryAllowsAnotherOwner(String status) {
        UUID previous = reservation(status);
        UUID next = reservation("ACTIVE");
        assertThat(next).isNotEqualTo(previous);
        assertThat(jdbc.queryForObject("select count(*) from reservations where seat_id = ?",
                Integer.class, seat)).isEqualTo(2);
    }

    @Test
    void reservationCannotHaveTwoPayments() {
        UUID reservation = reservation("ACTIVE");
        payment(reservation);
        assertThatThrownBy(() -> payment(reservation))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payments_reservation_id_key");
    }

    @Test
    void reservationMustExpireAfterCreation() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into reservations (user_id, seat_id, expires_at)
                values (?, ?, current_timestamp)
                """, UUID.randomUUID(), seat))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("reservations_positive_ttl");
    }

    private UUID reservation(String status) {
        return jdbc.queryForObject("""
                insert into reservations (user_id, seat_id, status, expires_at)
                values (?, ?, ?, current_timestamp + interval '5 minutes') returning id
                """, UUID.class, UUID.randomUUID(), seat, status);
    }

    private void payment(UUID reservation) {
        jdbc.update("""
                insert into payments (reservation_id, amount, simulation_mode)
                values (?, 10, 'SUCCESS')
                """, reservation);
    }
}
