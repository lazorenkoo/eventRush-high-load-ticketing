package com.lazorenkoo.eventrush.reservation;

import com.lazorenkoo.eventrush.relayforge.OutgoingEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReservationService {

    private final JdbcTemplate jdbc;
    private final OutgoingEvents events;
    private final long ttlMillis;

    public ReservationService(JdbcTemplate jdbc, OutgoingEvents events,
                              @Value("${eventrush.reservation.ttl:PT5M}") Duration ttl) {
        if (ttl.toMillis() <= 0) {
            throw new IllegalArgumentException("Reservation TTL must be at least one millisecond");
        }
        this.jdbc = jdbc;
        this.events = events;
        this.ttlMillis = ttl.toMillis();
    }

    @Transactional
    public Reservation reserve(UUID seatId, UUID userId, UUID gateId) {
        Objects.requireNonNull(userId, "User identity is required");
        Objects.requireNonNull(gateId, "Admission gate is required");
        Sale sale = jdbc.query("""
                select c.id, c.crowdgate_gate_id,
                    c.status = 'ON_SALE' and c.sale_starts_at <= clock_timestamp()
                        and c.starts_at > clock_timestamp() as open
                from concerts c join seats s on s.concert_id = c.id where s.id = ?
                """, (row, number) -> new Sale(row.getObject("id", UUID.class),
                row.getObject("crowdgate_gate_id", UUID.class), row.getBoolean("open")), seatId)
                .stream().findFirst().orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "SEAT_NOT_FOUND"));
        if (!sale.gateId().equals(gateId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMISSION_GATE_MISMATCH");
        }
        if (!sale.open()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SALE_NOT_OPEN");
        }
        int changed = jdbc.update("""
                update seats s set status = 'RESERVED' from concerts c
                where s.id = ? and s.status = 'AVAILABLE' and c.id = s.concert_id
                    and c.status = 'ON_SALE' and c.sale_starts_at <= clock_timestamp()
                    and c.starts_at > clock_timestamp() and c.crowdgate_gate_id = ?
                """, seatId, gateId);
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SEAT_ALREADY_RESERVED");
        }
        UUID id = UUID.randomUUID();
        Reservation reservation = jdbc.queryForObject("""
                insert into reservations (id, user_id, seat_id, created_at, expires_at)
                values (?, ?, ?, clock_timestamp(), clock_timestamp() + (? * interval '1 millisecond'))
                returning created_at, expires_at
                """, (row, number) -> new Reservation(id, userId, seatId, "ACTIVE",
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant()),
                id, userId, seatId, ttlMillis);
        events.enqueue("ticket.reserved", Map.of("reservationId", id, "seatId", seatId,
                "concertId", sale.concertId(), "userId", userId, "expiresAt", reservation.expiresAt()));
        return reservation;
    }

    public record Reservation(UUID id, UUID userId, UUID seatId, String status,
                              Instant createdAt, Instant expiresAt) {
    }

    private record Sale(UUID concertId, UUID gateId, boolean open) {
    }
}
