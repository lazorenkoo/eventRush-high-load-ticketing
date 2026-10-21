package com.lazorenkoo.eventrush.purchase;

import com.lazorenkoo.eventrush.downstream.DownstreamProcessor;
import com.lazorenkoo.eventrush.downstream.WebhookEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
class DownstreamJournalIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ParticipantService participants;
    @Autowired private ParticipantReservationService reservations;
    @Autowired private DownstreamProcessor processor;
    @Autowired private ObjectMapper mapper;
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

    private WebhookEvent event() throws Exception {
        return WebhookEvent.parse(mapper.writeValueAsBytes(java.util.Map.of("_eventrush",
                java.util.Map.of("id", reservation, "type", "ticket.purchased", "version", 1),
                "reservationId", reservation, "paymentId", reservation, "concertId", concert,
                "seatId", seat, "userId", owner, "amount", 25)));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from participant_activity where participant_id in "
                + "(select id from purchase_participants where concert_id=?)", concert);
        jdbc.update("delete from purchase_participants where concert_id=?", concert);
        jdbc.update("delete from ticket_records where event_id=?", reservation);
        jdbc.update("delete from sales_entries where event_id=?", reservation);
        jdbc.update("delete from simulated_notifications where event_id=?", reservation);
        jdbc.update("delete from webhook_receipts where event_id=?", reservation);
        jdbc.update("delete from payments where reservation_id=?", reservation);
        jdbc.update("delete from reservations where id=?", reservation);
        jdbc.update("delete from outgoing_events where payload::jsonb->>'concertId'=?", concert.toString());
        jdbc.update("delete from seats where concert_id=?", concert);
        jdbc.update("delete from concerts where id=?", concert);
    }

    private String output(DownstreamProcessor.Consumer consumer) {
        return switch (consumer) {
            case TICKETS -> "ticket_records";
            case ACCOUNTING -> "sales_entries";
            case NOTIFICATIONS -> "simulated_notifications";
        };
    }

    @ParameterizedTest
    @EnumSource(DownstreamProcessor.Consumer.class)
    void recordsOneJournalEntryAndOutputOnRedelivery(DownstreamProcessor.Consumer consumer) throws Exception {
        var event = event();
        String expected = switch (consumer) {
            case TICKETS -> "TICKET:ISSUED";
            case ACCOUNTING -> "ACCOUNTING:RECORDED";
            case NOTIFICATIONS -> "NOTIFICATION:SIMULATED";
        };
        processor.process(consumer, event);
        processor.process(consumer, event);
        assertThat(jdbc.queryForList("select kind || ':' || state from participant_activity where event_id=?",
                String.class, event.id())).containsExactlyInAnyOrder("RESERVATION:ACTIVE", expected);
        assertThat(jdbc.queryForObject("select count(*) from " + output(consumer) + " where event_id=?",
                Integer.class, event.id())).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(DownstreamProcessor.Consumer.class)
    void journalFailureRollsBackReceiptAndOutput(DownstreamProcessor.Consumer consumer) throws Exception {
        var event = event();
        ParticipantActivity target = AopTestUtils.getUltimateTargetObject(journal);
        doThrow(new IllegalStateException("Journal failure")).when(target)
                .forReservation(eq(reservation), eq(event.id()), any(), any());
        assertThatThrownBy(() -> processor.process(consumer, event)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from webhook_receipts where event_id=?",
                Integer.class, event.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from " + output(consumer) + " where event_id=?",
                Integer.class, event.id())).isZero();
    }
}