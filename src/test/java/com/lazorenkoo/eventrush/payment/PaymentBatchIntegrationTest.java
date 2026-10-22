package com.lazorenkoo.eventrush.payment;

import com.lazorenkoo.eventrush.reservation.ReservationService;
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
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_payment_batch_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "eventrush.expiration.enabled=false",
        "eventrush.payment.worker.enabled=false",
        "eventrush.payment.delay=PT1H"
})
class PaymentBatchIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private PaymentService payments;

    @Autowired
    private PaymentBatchService batch;

    @Autowired
    private PlatformTransactionManager transactions;

    private final UUID gate = UUID.randomUUID();
    private UUID seat;
    private UUID reservation;

    @BeforeEach
    void createDelayedPayment() {
        seat = jdbc.queryForObject("""
                with concert as (
                    insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id, status)
                    values ('Batch show', current_timestamp + interval '1 day', current_timestamp, ?, 'ON_SALE')
                    returning id
                )
                insert into seats (concert_id, section, seat_row, seat_number, price)
                select id, 'A', '1', 1, 10 from concert returning id
                """, UUID.class, gate);
        UUID owner = UUID.randomUUID();
        reservation = reservations.reserve(seat, owner, gate).id();
        payments.start(reservation, owner, PaymentService.Mode.DELAYED_SUCCESS);
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
    void batchWaitsForDueTimeAndDoesNotRepeatCompletedPayment() {
        assertThat(batch.completeDueBatch()).isZero();
        assertThat(jdbc.queryForObject("select status from payments where reservation_id = ?", String.class, reservation))
                .isEqualTo("PENDING");
        jdbc.update("update payments set complete_after = clock_timestamp() - interval '1 second' where reservation_id = ?",
                reservation);
        assertThat(batch.completeDueBatch()).isEqualTo(1);
        assertThat(batch.completeDueBatch()).isZero();
        assertThat(jdbc.queryForObject("select status from payments where reservation_id = ?", String.class, reservation))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("""
                select count(*) from outgoing_events
                where event_type = 'ticket.purchased' and payload::jsonb ->> 'reservationId' = ?
                """, Integer.class, reservation.toString())).isEqualTo(1);
    }

    @Test
    void concurrentWorkerSkipsAReservationLockedByAnotherTransaction() {
        jdbc.update("update payments set complete_after = clock_timestamp() - interval '1 second' where reservation_id = ?",
                reservation);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.queryForObject("select id from reservations where id = ? for update", UUID.class, reservation);
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(batch::completeDueBatch).get(3, TimeUnit.SECONDS)).isZero();
            } catch (Exception error) {
                throw new AssertionError("Payment worker blocked or failed on a locked reservation", error);
            }
        });
        assertThat(batch.completeDueBatch()).isEqualTo(1);
        assertThat(batch.completeDueBatch()).isZero();
    }
}
