package com.lazorenkoo.eventrush.concert;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class ConcertReadController {

    private final JdbcTemplate jdbc;

    public ConcertReadController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/concerts")
    public ResponseEntity<List<ConcertView>> list(@RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION");
        }
        var concerts = jdbc.query("""
                select id, name, starts_at, sale_starts_at, status, crowdgate_gate_id
                from concerts where status <> 'DRAFT'
                order by starts_at, id limit ? offset ?
                """, this::concert, limit, offset);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(concerts);
    }

    @GetMapping("/api/v1/concerts/{id}")
    public ResponseEntity<ConcertView> find(@PathVariable UUID id) {
        var concerts = jdbc.query("""
                select id, name, starts_at, sale_starts_at, status, crowdgate_gate_id
                from concerts where id = ? and status <> 'DRAFT'
                """, this::concert, id);
        if (concerts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CONCERT_NOT_FOUND");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(concerts.getFirst());
    }

    private ConcertView concert(ResultSet row, int index) throws SQLException {
        return new ConcertView(row.getObject("id", UUID.class), row.getString("name"),
                row.getTimestamp("starts_at").toInstant(), row.getTimestamp("sale_starts_at").toInstant(),
                row.getString("status"), row.getObject("crowdgate_gate_id", UUID.class));
    }

    public record ConcertView(UUID id, String name, Instant startsAt, Instant saleStartsAt,
                              String status, UUID crowdgateGateId) {
    }
}
