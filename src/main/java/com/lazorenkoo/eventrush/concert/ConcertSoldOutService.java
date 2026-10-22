package com.lazorenkoo.eventrush.concert;

import com.lazorenkoo.eventrush.relayforge.OutgoingEvents;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class ConcertSoldOutService {

    private final JdbcTemplate jdbc;
    private final OutgoingEvents events;

    public ConcertSoldOutService(JdbcTemplate jdbc, OutgoingEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional(timeout = 10)
    public int markSoldOutBatch() {
        var concerts = jdbc.queryForList("""
                select c.id from concerts c
                where c.status = 'ON_SALE'
                  and exists (select 1 from seats s where s.concert_id = c.id)
                  and not exists (select 1 from seats s where s.concert_id = c.id and s.status <> 'SOLD')
                order by c.id limit 100 for update of c skip locked
                """, UUID.class);
        int changed = 0;
        for (UUID concert : concerts) {
            int updated = jdbc.update("""
                    update concerts c set status = 'SOLD_OUT'
                    where c.id = ? and c.status = 'ON_SALE'
                      and exists (select 1 from seats s where s.concert_id = c.id)
                      and not exists (select 1 from seats s where s.concert_id = c.id and s.status <> 'SOLD')
                    """, concert);
            if (updated == 1) {
                events.enqueue("concert.sold_out", Map.of("concertId", concert));
                changed++;
            }
        }
        return changed;
    }
}
