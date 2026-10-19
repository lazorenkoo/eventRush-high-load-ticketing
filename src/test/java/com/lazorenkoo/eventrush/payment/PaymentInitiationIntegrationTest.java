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

import java.time.Duration;
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
class PaymentInitiationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private PaymentService payments;

    private final UUID owner = UUID.randomUUID();
    private UUID reservation;

    @BeforeEach
    void createActiveReservation() {
        UUID gate = UUID.randomUUID();
        UUID seat = jdbc.queryForObject("""
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
    void repeatedInitiationKeepsOnePaymentAndItsStoredSchedule(PaymentService.Mode mode) {
        var first = payments.start(reservation, owner, mode);
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.amount()).isEqualByComparingTo("10.00");
        assertThat(first.mode()).isEqualTo(mode);
        assertThat(payments.start(reservation, owner, mode)).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from payments where reservation_id = ?",
                Integer.class, reservation)).isEqualTo(1);
        if (mode == PaymentService.Mode.DELAYED_SUCCESS) {
            assertThat(Duration.between(first.createdAt(), first.completeAfter()).toMillis()).isGreaterThanOrEqualTo(9990);
        }
    }

    @Test
    void anotherUserCannotStartPayment() {
        assertThatThrownBy(() -> payments.start(reservation, UUID.randomUUID(), PaymentService.Mode.SUCCESS))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        assertThat(jdbc.queryForObject("select count(*) from payments where reservation_id = ?",
                Integer.class, reservation)).isZero();
    }

    @Test
    void repeatedRequestCannotChangeTheSimulationMode() {
        payments.start(reservation, owner, PaymentService.Mode.FAILED);
        assertThatThrownBy(() -> payments.start(reservation, owner, PaymentService.Mode.SUCCESS))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void expiredReservationCannotStartPayment() {
        jdbc.update("""
                update reservations set created_at = clock_timestamp() - interval '2 minutes',
                    expires_at = clock_timestamp() - interval '1 minute' where id = ?
                """, reservation);
        assertThatThrownBy(() -> payments.start(reservation, owner, PaymentService.Mode.SUCCESS))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThat(jdbc.queryForObject("select count(*) from payments where reservation_id = ?",
                Integer.class, reservation)).isZero();
    }
}
