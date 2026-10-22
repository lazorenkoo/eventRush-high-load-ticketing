package com.lazorenkoo.eventrush.purchase;

import com.lazorenkoo.eventrush.payment.PaymentService;
import com.lazorenkoo.eventrush.payment.PaymentCompletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
class PaymentJournalIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ParticipantService participants;
    @Autowired private ParticipantReservationService reservations;
    @Autowired private PaymentService payments;
    @Autowired private PaymentCompletionService completion;
    @MockitoSpyBean private ParticipantActivity journal;
    private UUID owner;
    private UUID concert;
    private UUID seat;
    private UUID reservation;

    @BeforeEach
    void setup() {
        owner = UUID.randomUUID();
        UUID gate = UUID.randomUUID();
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id,status)
                values ('Payment journal',now()+interval '1 day',now(),?,'ON_SALE') returning id
                """, UUID.class, gate);
        seat = jdbc.queryForObject("""
                insert into seats(concert_id,section,seat_row,seat_number,price)
                values (?,'A','1',1,25) returning id
                """, UUID.class, concert);
        UUID participant = participants.createManual(owner, concert, seat, "First", "Last");
        reservation = reservations.reserve(participant, seat, owner, gate).id();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from participant_activity where participant_id in "
                + "(select id from purchase_participants where concert_id=?)", concert);
        jdbc.update("delete from purchase_participants where concert_id=?", concert);
        jdbc.update("delete from payments where reservation_id=?", reservation);
        jdbc.update("delete from reservations where id=?", reservation);
        jdbc.update("delete from outgoing_events where payload::jsonb->>'concertId'=?", concert.toString());
        jdbc.update("delete from seats where concert_id=?", concert);
        jdbc.update("delete from concerts where id=?", concert);
    }

    private int paymentEvents() {
        return jdbc.queryForObject("select count(*) from participant_activity a join purchase_participants p "
                + "on p.id=a.participant_id where p.concert_id=? and a.kind='PAYMENT'", Integer.class, concert);
    }

    @ParameterizedTest
    @EnumSource(PaymentService.Mode.class)
    void recordsPendingAndOutcomeOnceForEverySimulation(PaymentService.Mode mode) {
        var payment = payments.start(reservation, owner, mode);
        assertThat(payments.start(reservation, owner, mode).id()).isEqualTo(payment.id());
        assertThat(paymentEvents()).isEqualTo(1);
        jdbc.update("update payments set complete_after=now()-interval '1 second' where id=?", payment.id());
        String expected = mode == PaymentService.Mode.FAILED ? "FAILED" : "SUCCESS";
        assertThat(completion.complete(reservation)).isEqualTo(expected);
        assertThat(completion.complete(reservation)).isEqualTo(expected);
        assertThat(paymentEvents()).isEqualTo(2);
    }

    @Test
    void expiredReservationProducesFailedPaymentAndExpiredReservationEvent() {
        payments.start(reservation, owner, PaymentService.Mode.SUCCESS);
        jdbc.update("update reservations set created_at=now()-interval '2 minutes', "
                + "expires_at=now()-interval '1 minute' where id=?", reservation);
        assertThat(completion.complete(reservation)).isEqualTo("FAILED");
        assertThat(paymentEvents()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from participant_activity a join purchase_participants p "
                + "on p.id=a.participant_id where p.concert_id=? and a.state='EXPIRED'", Integer.class, concert)).isEqualTo(1);
    }

    @Test
    void outcomeJournalFailureRollsBackPaymentReservationAndSeat() {
        payments.start(reservation, owner, PaymentService.Mode.SUCCESS);
        ParticipantActivity target = AopTestUtils.getUltimateTargetObject(journal);
        doThrow(new IllegalStateException("Journal failure")).when(target)
                .forReservation(eq(reservation), any(), eq(ParticipantActivity.Kind.PAYMENT), eq("SUCCESS"));
        assertThatThrownBy(() -> completion.complete(reservation)).isInstanceOf(IllegalStateException.class);
        assertThat(paymentEvents()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from payments where reservation_id=?", String.class, reservation))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status from reservations where id=?", String.class, reservation))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from seats where id=?", String.class, seat)).isEqualTo("RESERVED");
    }
}
