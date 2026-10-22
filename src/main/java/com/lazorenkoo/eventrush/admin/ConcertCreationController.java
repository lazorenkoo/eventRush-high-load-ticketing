package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.concert.ConcertReadController.ConcertView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@RestController
public class ConcertCreationController {

    private final JdbcTemplate jdbc;

    public ConcertCreationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/api/v1/admin/concerts")
    public ResponseEntity<ConcertView> create(@Valid @RequestBody CreateConcert request) {
        if (request.saleStartsAt().isAfter(request.startsAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SALE_WINDOW");
        }
        try {
            ConcertView concert = jdbc.queryForObject("""
                    insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id)
                    values (?, ?, ?, ?)
                    returning id, name, starts_at, sale_starts_at, status, crowdgate_gate_id
                    """, (row, index) -> new ConcertView(row.getObject("id", UUID.class), row.getString("name"),
                    row.getTimestamp("starts_at").toInstant(), row.getTimestamp("sale_starts_at").toInstant(),
                    row.getString("status"), row.getObject("crowdgate_gate_id", UUID.class)),
                    request.name().strip(), Timestamp.from(request.startsAt()), Timestamp.from(request.saleStartsAt()),
                    request.crowdgateGateId());
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(concert);
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CROWDGATE_GATE_ALREADY_ASSIGNED");
        }
    }

    public record CreateConcert(@NotBlank @Size(max = 255) String name,
                                @NotNull @Future Instant startsAt, @NotNull Instant saleStartsAt,
                                @NotNull UUID crowdgateGateId) {
    }
}
