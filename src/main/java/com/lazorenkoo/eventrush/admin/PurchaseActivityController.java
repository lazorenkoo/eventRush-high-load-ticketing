package com.lazorenkoo.eventrush.admin;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class PurchaseActivityController {
    private final JdbcTemplate jdbc;

    public PurchaseActivityController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/admin/concerts/{concertId}/activity")
    public ResponseEntity<List<Activity>> list(@PathVariable UUID concertId,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String state) {
        q = q.strip();
        if (limit < 1 || limit > 100 || offset < 0 || offset > 50000 || q.length() > 100
                || !Set.of("", "MANUAL", "TEST").contains(source)
                || !Set.of("", "REGISTERED", "RESERVED", "PAYING", "PAYMENT_FAILED", "PURCHASED",
                        "EXPIRED", "CANCELLED").contains(state)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ACTIVITY_QUERY");
        }
        var items = jdbc.query("""
                with activity as (
                    select p.id, p.first_name, p.last_name, p.source, p.created_at,
                        s.id as seat_id, s.section, s.seat_row, s.seat_number,
                        r.id as reservation_id, pay.status as payment_status, t.ticket_number,
                        case when r.status in ('EXPIRED','CANCELLED') then r.status
                            when r.status = 'CONFIRMED' then 'PURCHASED'
                            when pay.status = 'PENDING' then 'PAYING'
                            when pay.status = 'FAILED' then 'PAYMENT_FAILED'
                            when r.id is not null then 'RESERVED' else 'REGISTERED' end as state
                    from purchase_participants p join seats s on s.id = p.preferred_seat_id
                    left join reservations r on r.id = p.reservation_id
                    left join payments pay on pay.reservation_id = r.id
                    left join ticket_records t on t.reservation_id = r.id
                    where p.concert_id = ? and (? = '' or p.source = ?)
                )
                select * from activity
                where (? = '' or state = ?) and (? = '' or position(lower(?) in lower(concat_ws(' ',
                    first_name,last_name,section,seat_row,seat_number,id,seat_id,reservation_id))) > 0)
                order by created_at desc, id desc limit ? offset ?
                """, (row, index) -> new Activity(row.getObject("id", UUID.class),
                row.getString("first_name"), row.getString("last_name"), row.getString("source"),
                row.getString("state"), row.getObject("seat_id", UUID.class), row.getString("section"),
                row.getString("seat_row"), row.getInt("seat_number"), row.getObject("reservation_id", UUID.class),
                row.getString("payment_status"), row.getObject("ticket_number", Long.class),
                row.getTimestamp("created_at").toInstant()),
                concertId, source, source, state, state, q, q, limit, offset);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(items);
    }

    public record Activity(UUID id, String firstName, String lastName, String source, String state,
                           UUID seatId, String section, String row, int seatNumber, UUID reservationId,
                           String paymentStatus, Long ticketNumber, Instant createdAt) {
    }
}
