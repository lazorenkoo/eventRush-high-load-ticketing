package com.lazorenkoo.eventrush.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentBatchService {

    private final JdbcTemplate jdbc;
    private final PaymentCompletionService completion;

    public PaymentBatchService(JdbcTemplate jdbc, PaymentCompletionService completion) {
        this.jdbc = jdbc;
        this.completion = completion;
    }

    @Transactional(timeout = 15)
    public int completeDueBatch() {
        var reservations = jdbc.query("""
                select r.id from reservations r join payments p on p.reservation_id = r.id
                where p.status = 'PENDING' and p.complete_after <= clock_timestamp()
                order by p.complete_after, p.id limit 100
                for update of r skip locked
                """, (row, number) -> row.getObject("id", UUID.class));
        for (UUID reservation : reservations) {
            completion.complete(reservation);
        }
        return reservations.size();
    }
}
