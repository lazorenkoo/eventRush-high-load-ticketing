package com.lazorenkoo.eventrush.payment;

import com.lazorenkoo.eventrush.relayforge.OutgoingEvents;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentCompletionService {

    private final JdbcTemplate jdbc;
    private final OutgoingEvents events;

    public PaymentCompletionService(JdbcTemplate jdbc, OutgoingEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional(timeout = 10)
    public String complete(UUID reservationId) {
        Reservation reservation = jdbc.query("""
                select r.seat_id, r.user_id, r.status, s.concert_id
                from reservations r join seats s on s.id = r.seat_id
                where r.id = ? for update of r
                """, (row, number) -> new Reservation(row.getObject("seat_id", UUID.class),
                row.getObject("user_id", UUID.class), row.getObject("concert_id", UUID.class),
                row.getString("status")), reservationId).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND"));
        StoredPayment payment = jdbc.query("""
                select id, amount, status, simulation_mode, complete_after <= clock_timestamp() as due
                from payments where reservation_id = ? for update
                """, (row, number) -> new StoredPayment(row.getObject("id", UUID.class),
                row.getBigDecimal("amount"), row.getString("status"), row.getString("simulation_mode"),
                row.getBoolean("due")), reservationId).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND"));
        if (!payment.status().equals("PENDING") || !payment.due()) {
            return payment.status();
        }
        if (!reservation.status().equals("ACTIVE")) {
            finish(payment.id(), "FAILED");
            return "FAILED";
        }
        String reservationStatus = jdbc.queryForObject("""
                update reservations set status = case
                    when expires_at <= clock_timestamp() then 'EXPIRED'
                    when ? then 'CANCELLED' else 'CONFIRMED' end
                where id = ? returning status
                """, String.class, payment.mode().equals("FAILED"), reservationId);
        boolean success = "CONFIRMED".equals(reservationStatus);
        int changed = jdbc.update("update seats set status = ? where id = ? and status = 'RESERVED'",
                success ? "SOLD" : "AVAILABLE", reservation.seatId());
        if (changed != 1) {
            throw new IllegalStateException("Reserved seat is missing during payment completion");
        }
        String paymentStatus = success ? "SUCCESS" : "FAILED";
        finish(payment.id(), paymentStatus);
        String type = "EXPIRED".equals(reservationStatus) ? "reservation.expired"
                : success ? "ticket.purchased" : "payment.failed";
        events.enqueue(type, Map.of("paymentId", payment.id(), "reservationId", reservationId,
                "seatId", reservation.seatId(), "concertId", reservation.concertId(),
                "userId", reservation.userId(), "amount", payment.amount()));
        return paymentStatus;
    }

    private void finish(UUID paymentId, String status) {
        jdbc.update("update payments set status = ?, completed_at = clock_timestamp() where id = ?",
                status, paymentId);
    }

    private record Reservation(UUID seatId, UUID userId, UUID concertId, String status) {
    }

    private record StoredPayment(UUID id, BigDecimal amount, String status, String mode, boolean due) {
    }
}
