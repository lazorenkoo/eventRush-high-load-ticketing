package com.lazorenkoo.eventrush.downstream;

import com.lazorenkoo.eventrush.purchase.ParticipantActivity;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
public class DownstreamProcessor {

    public enum Consumer { TICKETS, ACCOUNTING, NOTIFICATIONS }

    private final JdbcTemplate jdbc;
    private final ParticipantActivity activity;
    private final boolean failFirstNotification;
    private final TransactionTemplate failureTransactions;

    public DownstreamProcessor(JdbcTemplate jdbc, PlatformTransactionManager transactions, ParticipantActivity activity,
            @Value("${eventrush.downstream.demo.fail-first-notification:false}") boolean failFirstNotification) {
        this.jdbc = jdbc;
        this.activity = activity;
        this.failFirstNotification = failFirstNotification;
        this.failureTransactions = new TransactionTemplate(transactions);
        this.failureTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.failureTransactions.setTimeout(10);
    }

    @Transactional(timeout = 10)
    public void process(Consumer consumer, WebhookEvent event) {
        if (failFirstNotification && consumer == Consumer.NOTIFICATIONS && event.type().equals("ticket.purchased")) {
            Boolean first = failureTransactions.execute(status -> jdbc.update("""
                    insert into notification_demo_failures(event_id) values (?) on conflict do nothing
                    """, event.id()) == 1);
            if (Boolean.TRUE.equals(first)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DEMO_NOTIFICATION_UNAVAILABLE");
            }
        }
        int inserted = jdbc.update("""
                insert into webhook_receipts(consumer, event_id, event_type, payload_sha256)
                values (?, ?, ?, ?) on conflict (consumer, event_id) do nothing
                """, consumer.name(), event.id(), event.type(), event.sha256());
        if (inserted == 0) {
            Boolean matches = jdbc.queryForObject("""
                    select event_type = ? and payload_sha256 = ? from webhook_receipts
                    where consumer = ? and event_id = ?
                    """, Boolean.class, event.type(), event.sha256(), consumer.name(), event.id());
            if (!Boolean.TRUE.equals(matches)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "WEBHOOK_EVENT_CONFLICT");
            }
            return;
        }
        if (consumer != Consumer.NOTIFICATIONS && !event.type().equals("ticket.purchased")) {
            throw invalid();
        }
        switch (consumer) {
            case TICKETS -> jdbc.update("""
                    insert into ticket_records(event_id, reservation_id, concert_id, seat_id, user_id)
                    values (?, ?, ?, ?, ?)
                    """, event.id(), uuid(event, "reservationId"), uuid(event, "concertId"),
                    uuid(event, "seatId"), uuid(event, "userId"));
            case ACCOUNTING -> jdbc.update("""
                    insert into sales_entries(event_id, payment_id, reservation_id, concert_id, amount)
                    values (?, ?, ?, ?, ?)
                    """, event.id(), uuid(event, "paymentId"), uuid(event, "reservationId"),
                    uuid(event, "concertId"), amount(event));
            case NOTIFICATIONS -> jdbc.update("""
                    insert into simulated_notifications(event_id, event_type, user_id, payload)
                    values (?, ?, ?, ?)
                    """, event.id(), event.type(), event.type().equals("concert.sold_out")
                    ? null : uuid(event, "userId"), event.payload());
        }
        recordActivity(consumer, event);
    }

    private void recordActivity(Consumer consumer, WebhookEvent event) {
        if (event.type().equals("concert.sold_out")) return;
        ParticipantActivity.Kind kind = switch (consumer) {
            case TICKETS -> ParticipantActivity.Kind.TICKET;
            case ACCOUNTING -> ParticipantActivity.Kind.ACCOUNTING;
            case NOTIFICATIONS -> ParticipantActivity.Kind.NOTIFICATION;
        };
        String state = switch (consumer) {
            case TICKETS -> "ISSUED";
            case ACCOUNTING -> "RECORDED";
            case NOTIFICATIONS -> "SIMULATED";
        };
        activity.forReservation(uuid(event, "reservationId"), event.id(), kind, state);
    }

    private UUID uuid(WebhookEvent event, String field) {
        var value = event.data().path(field);
        if (!value.isTextual()) {
            throw invalid();
        }
        try {
            UUID id = UUID.fromString(value.textValue());
            if (!id.toString().equals(value.textValue())) {
                throw invalid();
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private BigDecimal amount(WebhookEvent event) {
        var value = event.data().path("amount");
        if (!value.isNumber()) {
            throw invalid();
        }
        try {
            BigDecimal amount = value.decimalValue().setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() <= 0 || amount.precision() > 12) {
                throw invalid();
            }
            return amount;
        } catch (ArithmeticException exception) {
            throw invalid();
        }
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK_DATA");
    }
}
