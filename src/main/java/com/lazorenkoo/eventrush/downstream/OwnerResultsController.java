package com.lazorenkoo.eventrush.downstream;

import com.lazorenkoo.eventrush.admission.AdmissionSessionStore;
import com.lazorenkoo.eventrush.admission.SessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class OwnerResultsController {

    private final JdbcTemplate jdbc;
    private final AdmissionSessionStore sessions;
    private final SessionCookie cookies;

    public OwnerResultsController(JdbcTemplate jdbc, AdmissionSessionStore sessions, SessionCookie cookies) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.cookies = cookies;
    }

    @GetMapping("/api/v1/me/tickets")
    public ResponseEntity<List<Ticket>> tickets(@RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset, HttpServletRequest request) {
        UUID owner = owner(request, limit, offset);
        var results = jdbc.query("""
                select ticket_number, reservation_id, concert_id, seat_id, issued_at from ticket_records
                where user_id = ? order by issued_at desc, ticket_number desc limit ? offset ?
                """, (row, index) -> new Ticket(row.getLong("ticket_number"),
                row.getObject("reservation_id", UUID.class), row.getObject("concert_id", UUID.class),
                row.getObject("seat_id", UUID.class), row.getTimestamp("issued_at").toInstant()), owner, limit, offset);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(results);
    }

    @GetMapping("/api/v1/me/notifications")
    public ResponseEntity<List<Notification>> notifications(@RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset, HttpServletRequest request) {
        UUID owner = owner(request, limit, offset);
        var results = jdbc.query("""
                select id, event_id, event_type, created_at from simulated_notifications
                where user_id = ? order by created_at desc, id desc limit ? offset ?
                """, (row, index) -> new Notification(row.getLong("id"), row.getObject("event_id", UUID.class),
                row.getString("event_type"), row.getTimestamp("created_at").toInstant()), owner, limit, offset);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(results);
    }

    private UUID owner(HttpServletRequest request, int limit, int offset) {
        UUID owner = sessions.require(cookies.require(request)).admission().visitorId();
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION");
        }
        return owner;
    }

    public record Ticket(long ticketNumber, UUID reservationId, UUID concertId, UUID seatId, Instant issuedAt) {
    }

    public record Notification(long id, UUID eventId, String eventType, Instant createdAt) {
    }
}
