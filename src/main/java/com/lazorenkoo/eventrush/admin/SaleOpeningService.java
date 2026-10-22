package com.lazorenkoo.eventrush.admin;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class SaleOpeningService {

    private final JdbcTemplate jdbc;

    public SaleOpeningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(timeout = 10)
    public void open(UUID concertId) {
        var states = jdbc.queryForList("select status from concerts where id = ? for update", String.class, concertId);
        if (states.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CONCERT_NOT_FOUND");
        }
        if ("ON_SALE".equals(states.getFirst())) {
            return;
        }
        if (!"DRAFT".equals(states.getFirst())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CONCERT_NOT_DRAFT");
        }
        int changed = jdbc.update("""
                update concerts c set status = 'ON_SALE'
                where c.id = ? and c.status = 'DRAFT' and c.starts_at > clock_timestamp()
                  and exists (select 1 from seats s where s.concert_id = c.id)
                  and not exists (select 1 from seats s where s.concert_id = c.id and s.status <> 'AVAILABLE')
                """, concertId);
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CONCERT_NOT_READY_FOR_SALE");
        }
    }
}
