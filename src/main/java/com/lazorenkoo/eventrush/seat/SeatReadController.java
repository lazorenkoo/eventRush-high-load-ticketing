package com.lazorenkoo.eventrush.seat;

import com.lazorenkoo.eventrush.admission.AdmissionService;
import com.lazorenkoo.eventrush.admission.SessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "eventrush.crowdgate.enabled", havingValue = "true")
public class SeatReadController {

    private final JdbcTemplate jdbc;
    private final AdmissionService admissions;
    private final SessionCookie cookies;

    public SeatReadController(JdbcTemplate jdbc, AdmissionService admissions, SessionCookie cookies) {
        this.jdbc = jdbc;
        this.admissions = admissions;
        this.cookies = cookies;
    }

    @GetMapping("/api/v1/concerts/{id}/seats")
    public ResponseEntity<List<SeatView>> list(@PathVariable UUID id,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        String token = cookies.require(request);
        if (limit < 1 || limit > 500 || offset < 0 || offset > 100000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION");
        }
        admissions.requireActiveOwner(token, id);
        var seats = jdbc.query("""
                select id, section, seat_row, seat_number, price, status from seats where concert_id = ?
                order by section, seat_row, seat_number, id limit ? offset ?
                """, (row, index) -> new SeatView(row.getObject("id", UUID.class), row.getString("section"),
                row.getString("seat_row"), row.getInt("seat_number"), row.getBigDecimal("price"),
                row.getString("status")), id, limit, offset);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(seats);
    }

    public record SeatView(UUID id, String section, String row, int number, BigDecimal price, String status) {
    }
}
