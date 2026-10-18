package com.lazorenkoo.eventrush.reservation;

import com.lazorenkoo.eventrush.relayforge.OutgoingEvents;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class ReservationExpirationService {

    private final JdbcTemplate jdbc;
    private final OutgoingEvents events;

    public ReservationExpirationService(JdbcTemplate jdbc, OutgoingEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional(timeout = 10)
    public int expireBatch() {
        var expired = jdbc.query("""
                select r.id, r.user_id, r.seat_id, s.concert_id
                from reservations r join seats s on s.id = r.seat_id
                where r.status = 'ACTIVE' and r.expires_at <= clock_timestamp()
                order by r.expires_at, r.id limit 100
                for update of r skip locked
                """, (row, number) -> new Candidate(row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class), row.getObject("seat_id", UUID.class),
                row.getObject("concert_id", UUID.class)));
        for (Candidate reservation : expired) {
            jdbc.update("update reservations set status = 'EXPIRED' where id = ?", reservation.id());
            int released = jdbc.update("update seats set status = 'AVAILABLE' where id = ? and status = 'RESERVED'",
                    reservation.seatId());
            if (released != 1) {
                throw new IllegalStateException("Reserved seat is missing during expiration");
            }
            jdbc.update("""
                    update payments set status = 'FAILED', completed_at = clock_timestamp()
                    where reservation_id = ? and status = 'PENDING'
                    """, reservation.id());
            events.enqueue("reservation.expired", Map.of("reservationId", reservation.id(),
                    "seatId", reservation.seatId(), "concertId", reservation.concertId(),
                    "userId", reservation.userId()));
        }
        return expired.size();
    }

    private record Candidate(UUID id, UUID userId, UUID seatId, UUID concertId) {
    }
}
