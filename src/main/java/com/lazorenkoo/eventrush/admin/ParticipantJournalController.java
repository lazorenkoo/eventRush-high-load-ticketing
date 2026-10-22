package com.lazorenkoo.eventrush.admin;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
public class ParticipantJournalController {
    private final JdbcTemplate jdbc;

    public ParticipantJournalController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/admin/concerts/{concertId}/journal")
    @Transactional(timeout = 3)
    public ResponseEntity<Page> list(@PathVariable UUID concertId,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String kind) {
        q = q.strip();
        if (after < 0 || limit < 1 || limit > 100 || q.length() > 100
                || !Set.of("", "MANUAL", "TEST").contains(source)
                || !Set.of("", "REGISTRATION", "QUEUE", "RESERVATION", "PAYMENT", "TICKET",
                        "ACCOUNTING", "NOTIFICATION").contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_JOURNAL_QUERY");
        }
        jdbc.execute("set local lock_timeout = '1s'");
        // Wait for earlier inserts to commit before advancing a sequence-based cursor.
        jdbc.execute("lock table participant_activity in share mode");
        var found = jdbc.query("""
                select a.id, a.event_id, a.kind, a.state, a.recorded_at,
                    p.id as participant_id, p.first_name, p.last_name, p.source, p.reservation_id,
                    s.id as seat_id, s.section, s.seat_row, s.seat_number
                from participant_activity a join purchase_participants p on p.id = a.participant_id
                join seats s on s.id = p.preferred_seat_id
                where p.concert_id = ? and a.id > ? and (? = '' or p.source = ?)
                    and (? = '' or a.kind = ?) and (? = '' or position(lower(?) in lower(concat_ws(' ',
                        p.first_name,p.last_name,s.section,s.seat_row,s.seat_number,
                        p.id,s.id,p.reservation_id,a.event_id))) > 0)
                order by a.id limit ?
                """, (row, index) -> new Entry(row.getLong("id"), row.getObject("event_id", UUID.class),
                row.getObject("participant_id", UUID.class), row.getString("first_name"),
                row.getString("last_name"), row.getString("source"), row.getString("kind"),
                row.getString("state"), row.getTimestamp("recorded_at").toInstant(),
                row.getObject("seat_id", UUID.class), row.getString("section"), row.getString("seat_row"),
                row.getInt("seat_number"), row.getObject("reservation_id", UUID.class)),
                concertId, after, source, source, kind, kind, q, q, limit + 1);
        boolean hasMore = found.size() > limit;
        List<Entry> items = List.copyOf(found.subList(0, Math.min(limit, found.size())));
        long next = items.isEmpty() ? after : items.getLast().id();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(items, next, hasMore));
    }

    public record Page(List<Entry> items, long nextAfter, boolean hasMore) {
    }

    public record Entry(long id, UUID eventId, UUID participantId, String firstName, String lastName,
                        String source, String kind, String state, Instant occurredAt, UUID seatId,
                        String section, String row, int seatNumber, UUID reservationId) {
    }
}