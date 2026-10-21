package com.lazorenkoo.eventrush.purchase;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

@Service
public class ParticipantService {
    private final JdbcTemplate jdbc;

    public ParticipantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID createManual(UUID owner, UUID concert, UUID seat, String firstName, String lastName) {
        return create(owner, concert, seat, firstName, lastName, null);
    }

    @Transactional
    public UUID createTest(UUID owner, UUID concert, UUID seat, String firstName, String lastName, UUID run) {
        return create(owner, concert, seat, firstName, lastName, Objects.requireNonNull(run, "run"));
    }

    private UUID create(UUID owner, UUID concert, UUID seat, String firstName, String lastName, UUID run) {
        Objects.requireNonNull(owner, "owner");
        String first = name(firstName);
        String last = name(lastName);
        var ids = jdbc.queryForList("""
                insert into purchase_participants(user_id, concert_id, preferred_seat_id,
                    first_name, last_name, source, run_id)
                select ?, c.id, s.id, ?, ?, ?, ? from concerts c
                join seats s on s.concert_id = c.id
                where c.id = ? and s.id = ? and c.status = 'ON_SALE'
                returning id
                """, UUID.class, owner, first, last, run == null ? "MANUAL" : "TEST", run, concert, seat);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PURCHASE_SEAT_UNAVAILABLE");
        return ids.getFirst();
    }

    @Transactional
    public void linkReservation(UUID participant, UUID owner, UUID reservation) {
        int updated = jdbc.update("""
                update purchase_participants p set reservation_id = r.id
                from reservations r
                where p.id = ? and p.user_id = ? and r.id = ?
                    and r.user_id = p.user_id and r.seat_id = p.preferred_seat_id
                    and (p.reservation_id is null or p.reservation_id = r.id)
                """, participant, owner, reservation);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PARTICIPANT_RESERVATION_NOT_FOUND");
    }

    private static String name(String value) {
        if (value == null) throw invalidName();
        String cleaned = value.strip();
        if (cleaned.isEmpty() || cleaned.codePointCount(0, cleaned.length()) > 100
                || cleaned.codePoints().anyMatch(Character::isISOControl)) throw invalidName();
        return cleaned;
    }

    private static ResponseStatusException invalidName() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CUSTOMER_NAME");
    }
}
