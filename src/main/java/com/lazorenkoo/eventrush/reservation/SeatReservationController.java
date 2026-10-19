package com.lazorenkoo.eventrush.reservation;

import com.lazorenkoo.eventrush.admission.AdmissionService;
import com.lazorenkoo.eventrush.admission.AdmissionSessionStore;
import com.lazorenkoo.eventrush.admission.SessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "eventrush.crowdgate.enabled", havingValue = "true")
public class SeatReservationController {

    private final JdbcTemplate jdbc;
    private final SessionCookie cookies;
    private final AdmissionSessionStore sessions;
    private final AdmissionService admissions;
    private final ReservationService reservations;

    public SeatReservationController(JdbcTemplate jdbc, SessionCookie cookies, AdmissionSessionStore sessions,
            AdmissionService admissions, ReservationService reservations) {
        this.jdbc = jdbc;
        this.cookies = cookies;
        this.sessions = sessions;
        this.admissions = admissions;
        this.reservations = reservations;
    }

    @PostMapping("/api/v1/seats/{seatId}/reserve")
    public ResponseEntity<ReservationView> reserve(@PathVariable UUID seatId, HttpServletRequest request) {
        String token = cookies.require(request);
        var session = sessions.require(token);
        var concerts = jdbc.queryForList("select concert_id from seats where id = ?", UUID.class, seatId);
        if (concerts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SEAT_NOT_FOUND");
        }
        UUID owner = admissions.requireActiveOwner(token, concerts.getFirst());
        var reservation = reservations.reserve(seatId, owner, session.admission().gateId());
        var body = new ReservationView(reservation.id(), reservation.seatId(), reservation.status(),
                reservation.createdAt(), reservation.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    public record ReservationView(UUID id, UUID seatId, String status, Instant createdAt, Instant expiresAt) {
    }
}
