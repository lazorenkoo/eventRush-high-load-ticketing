package com.lazorenkoo.eventrush.reservation;

import com.lazorenkoo.eventrush.purchase.ParticipantActivity;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class ReservationCancellationService {

    private final JdbcTemplate jdbc;
    private final ParticipantActivity activity;

    public ReservationCancellationService(JdbcTemplate jdbc, ParticipantActivity activity) {
        this.jdbc = jdbc;
        this.activity = activity;
    }

    @Transactional
    public void cancel(UUID reservationId, UUID userId) {
        State reservation = jdbc.query("""
                select seat_id, status from reservations where id = ? and user_id = ? for update
                """, (row, number) -> new State(row.getObject("seat_id", UUID.class), row.getString("status")),
                reservationId, userId).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND"));
        if (reservation.status().equals("CANCELLED")) {
            return;
        }
        int cancelled = jdbc.update("""
                update reservations set status = 'CANCELLED'
                where id = ? and status = 'ACTIVE' and expires_at > clock_timestamp()
                """, reservationId);
        if (cancelled != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RESERVATION_NOT_ACTIVE");
        }
        int released = jdbc.update("update seats set status = 'AVAILABLE' where id = ? and status = 'RESERVED'",
                reservation.seatId());
        if (released != 1) {
            throw new IllegalStateException("Reserved seat is missing during cancellation");
        }
        activity.failPendingPayment(reservationId);
        activity.forReservation(reservationId, UUID.randomUUID(),
                ParticipantActivity.Kind.RESERVATION, "CANCELLED");
    }

    private record State(UUID seatId, String status) {
    }
}
