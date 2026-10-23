package com.lazorenkoo.eventrush.admin;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/concerts/{concertId}/events")
public class ConcertEventController {
    private final JdbcTemplate jdbc;

    public ConcertEventController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<Page> events(@PathVariable UUID concertId,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 50000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_EVENT_PAGE");
        }
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from concerts where id=?)",
                Boolean.class, concertId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CONCERT_NOT_FOUND");
        }
        var rows = jdbc.query("""
                select id,event_type,created_at,published_at,attempt_count,next_attempt_at
                from outgoing_events where payload::jsonb->>'concertId'=?
                order by created_at desc,id desc limit ? offset ?
                """, (row, index) -> new Event(row.getObject("id", UUID.class), row.getString("event_type"),
                row.getTimestamp("created_at").toInstant(), instant(row.getTimestamp("published_at")),
                row.getInt("attempt_count"), row.getTimestamp("next_attempt_at").toInstant()),
                concertId.toString(), limit + 1, offset);
        boolean hasMore = rows.size() > limit;
        boolean truncated = hasMore && offset + limit > 50000;
        var page = new Page(List.copyOf(rows.subList(0, Math.min(rows.size(), limit))), hasMore,
                hasMore && !truncated ? offset + limit : null, truncated);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record Event(UUID id, String type, Instant createdAt, Instant publishedAt,
                        int publishAttempts, Instant nextPublishAttemptAt) {}

    public record Page(List<Event> items, boolean hasMore, Integer nextOffset, boolean truncated) {}
}
