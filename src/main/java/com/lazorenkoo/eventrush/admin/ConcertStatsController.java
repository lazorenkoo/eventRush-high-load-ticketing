package com.lazorenkoo.eventrush.admin;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
public class ConcertStatsController {

    private final JdbcTemplate jdbc;

    public ConcertStatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/admin/concerts/{id}/stats")
    public ResponseEntity<ConcertStats> stats(@PathVariable UUID id) {
        var results = jdbc.query("""
                select c.id, c.status, inventory.*, payments.* from concerts c
                cross join lateral (
                    select count(*) as total_seats,
                        count(*) filter (where s.status = 'AVAILABLE') as available,
                        count(*) filter (where s.status = 'RESERVED') as reserved,
                        count(*) filter (where s.status = 'SOLD') as sold
                    from seats s where s.concert_id = c.id
                ) inventory
                cross join lateral (
                    select count(*) filter (where p.status = 'PENDING') as pending_payments,
                        count(*) filter (where p.status = 'SUCCESS') as successful_payments,
                        count(*) filter (where p.status = 'FAILED') as failed_payments,
                        coalesce(sum(p.amount) filter (where p.status = 'SUCCESS'), 0) as revenue
                    from payments p join reservations r on r.id = p.reservation_id
                    join seats s on s.id = r.seat_id where s.concert_id = c.id
                ) payments
                where c.id = ?
                """, (row, index) -> new ConcertStats(row.getObject("id", UUID.class), row.getString("status"),
                row.getLong("total_seats"), row.getLong("available"), row.getLong("reserved"), row.getLong("sold"),
                row.getLong("pending_payments"), row.getLong("successful_payments"), row.getLong("failed_payments"),
                row.getBigDecimal("revenue")), id);
        if (results.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CONCERT_NOT_FOUND");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(results.getFirst());
    }

    public record ConcertStats(UUID concertId, String status, long totalSeats, long available, long reserved,
                               long sold, long pendingPayments, long successfulPayments, long failedPayments,
                               BigDecimal revenue) {
    }
}
