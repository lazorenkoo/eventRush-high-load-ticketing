package com.lazorenkoo.eventrush.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentService {

    private final JdbcTemplate jdbc;
    private final long delayMillis;

    public PaymentService(JdbcTemplate jdbc, @Value("${eventrush.payment.delay:PT10S}") Duration delay) {
        if (delay.toMillis() <= 0) {
            throw new IllegalArgumentException("Payment delay must be at least one millisecond");
        }
        this.jdbc = jdbc;
        this.delayMillis = delay.toMillis();
    }

    @Transactional
    public Payment start(UUID reservationId, UUID userId, Mode mode) {
        Objects.requireNonNull(mode, "Simulation mode is required");
        var owned = jdbc.query("select id from reservations where id = ? and user_id = ? for update",
                (row, number) -> row.getObject("id", UUID.class), reservationId, userId);
        if (owned.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND");
        }
        var existing = jdbc.query("select * from payments where reservation_id = ?", this::payment, reservationId);
        if (!existing.isEmpty()) {
            Payment payment = existing.getFirst();
            if (payment.mode() != mode) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "PAYMENT_MODE_MISMATCH");
            }
            return payment;
        }
        var prices = jdbc.query("""
                select s.price from reservations r join seats s on s.id = r.seat_id
                where r.id = ? and r.status = 'ACTIVE' and r.expires_at > clock_timestamp()
                    and s.status = 'RESERVED'
                """, (row, number) -> row.getBigDecimal("price"), reservationId);
        if (prices.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RESERVATION_NOT_ACTIVE");
        }
        long delay = mode == Mode.DELAYED_SUCCESS ? delayMillis : 0;
        return jdbc.queryForObject("""
                insert into payments (id, reservation_id, amount, simulation_mode, created_at, complete_after)
                values (?, ?, ?, ?, clock_timestamp(), clock_timestamp() + (? * interval '1 millisecond'))
                returning *
                """, this::payment, UUID.randomUUID(), reservationId, prices.getFirst(), mode.name(), delay);
    }

    private Payment payment(ResultSet row, int number) throws SQLException {
        return new Payment(row.getObject("id", UUID.class), row.getObject("reservation_id", UUID.class),
                row.getBigDecimal("amount"), row.getString("status"),
                Mode.valueOf(row.getString("simulation_mode")), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("complete_after").toInstant());
    }

    public enum Mode {
        SUCCESS,
        FAILED,
        DELAYED_SUCCESS
    }

    public record Payment(UUID id, UUID reservationId, BigDecimal amount, String status,
                          Mode mode, Instant createdAt, Instant completeAfter) {
    }
}
