package com.lazorenkoo.eventrush.reservation;

import com.lazorenkoo.eventrush.admission.AdmissionSessionStore;
import com.lazorenkoo.eventrush.admission.SessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
public class ReservationReadController {

    private final JdbcTemplate jdbc;
    private final AdmissionSessionStore sessions;
    private final SessionCookie cookies;

    public ReservationReadController(JdbcTemplate jdbc, AdmissionSessionStore sessions, SessionCookie cookies) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.cookies = cookies;
    }

    @GetMapping("/api/v1/reservations/{id}")
    public ResponseEntity<SeatReservationController.ReservationView> find(@PathVariable UUID id,
            HttpServletRequest request) {
        UUID owner = sessions.require(cookies.require(request)).admission().visitorId();
        var reservations = jdbc.query("""
                select id, seat_id, status, created_at, expires_at from reservations
                where id = ? and user_id = ?
                """, (row, index) -> new SeatReservationController.ReservationView(row.getObject("id", UUID.class),
                row.getObject("seat_id", UUID.class), row.getString("status"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant()), id, owner);
        if (reservations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reservations.getFirst());
    }
}
