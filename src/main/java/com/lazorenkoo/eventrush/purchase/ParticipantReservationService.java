package com.lazorenkoo.eventrush.purchase;

import com.lazorenkoo.eventrush.reservation.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class ParticipantReservationService {
    private final JdbcTemplate jdbc;
    private final ReservationService reservations;
    private final ParticipantService participants;
    private final ParticipantActivity activity;

    public ParticipantReservationService(JdbcTemplate jdbc, ReservationService reservations,
                                         ParticipantService participants, ParticipantActivity activity) {
        this.jdbc = jdbc;
        this.reservations = reservations;
        this.participants = participants;
        this.activity = activity;
    }

    @Transactional
    public ReservationService.Reservation reserve(UUID participant, UUID seat, UUID owner, UUID gate) {
        var records = jdbc.query("""
                select p.reservation_id from purchase_participants p
                join concerts c on c.id = p.concert_id
                where p.id = ? and p.user_id = ? and p.preferred_seat_id = ?
                    and c.crowdgate_gate_id = ?
                for update of p
                """, (row, index) -> new Link(row.getObject("reservation_id", UUID.class)),
                participant, owner, seat, gate);
        if (records.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND");
        }
        UUID existing = records.getFirst().reservation();
        if (existing != null) {
            return jdbc.queryForObject("""
                    select id, user_id, seat_id, status, created_at, expires_at
                    from reservations where id = ? and user_id = ? and seat_id = ?
                    """, (row, index) -> new ReservationService.Reservation(
                    row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                    row.getObject("seat_id", UUID.class), row.getString("status"),
                    row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant()),
                    existing, owner, seat);
        }
        var reservation = reservations.reserve(seat, owner, gate);
        participants.linkReservation(participant, owner, reservation.id());
        activity.append(participant, reservation.id(), ParticipantActivity.Kind.RESERVATION, reservation.status());
        return reservation;
    }

    private record Link(UUID reservation) {
    }
}
