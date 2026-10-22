package com.lazorenkoo.eventrush.purchase;

import com.lazorenkoo.eventrush.payment.PaymentService;
import com.lazorenkoo.eventrush.reservation.ReservationCancellationService;
import com.lazorenkoo.eventrush.reservation.ReservationExpirationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class ReservationReleaseJournalIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ParticipantService participants;
    @Autowired private ParticipantReservationService reservations;
    @Autowired private PaymentService payments;
    @Autowired private ReservationCancellationService cancellation;
    @Autowired private ReservationExpirationService expiration;
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

    private void release(boolean expired) {
        if (expired) expiration.expireBatch();
        else cancellation.cancel(reservation, owner);
    }

    private void prepare(boolean expired) {
        payments.start(reservation, owner, PaymentService.Mode.DELAYED_SUCCESS);
        if (expired) jdbc.update("update reservations set created_at=now()-interval '2 minutes', "
                + "expires_at=now()-interval '1 minute' where id=?", reservation);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturesReleaseAndFailedPaymentOnce(boolean expired) {
        prepare(expired);
        release(expired);
        release(expired);
        assertThat(jdbc.queryForObject("select count(*) from participant_activity a join purchase_participants p "
                + "on p.id=a.participant_id where p.concert_id=?", Integer.class, concert)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from participant_activity a join purchase_participants p "
                + "on p.id=a.participant_id where p.concert_id=? and a.state=?", Integer.class,
                concert, expired ? "EXPIRED" : "CANCELLED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from payments where reservation_id=?", String.class, reservation))
                .isEqualTo("FAILED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void journalFailureRollsBackWholeRelease(boolean expired) {
        prepare(expired);
        ParticipantActivity target = AopTestUtils.getUltimateTargetObject(journal);
        doThrow(new IllegalStateException("Journal failure")).when(target).forReservation(eq(reservation), any(),
                eq(ParticipantActivity.Kind.RESERVATION), eq(expired ? "EXPIRED" : "CANCELLED"));
        assertThatThrownBy(() -> release(expired)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select status from reservations where id=?", String.class, reservation))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from seats where id=?", String.class, seat)).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("select status from payments where reservation_id=?", String.class, reservation))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from participant_activity a join purchase_participants p "
                + "on p.id=a.participant_id where p.concert_id=?", Integer.class, concert)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from outgoing_events where event_type='reservation.expired' "
                + "and payload::jsonb->>'concertId'=?", Integer.class, concert.toString())).isZero();
    }
}