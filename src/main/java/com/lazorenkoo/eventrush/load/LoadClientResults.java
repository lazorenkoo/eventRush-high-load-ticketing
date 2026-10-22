package com.lazorenkoo.eventrush.load;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class LoadClientResults {
    private final JdbcTemplate jdbc;

    public LoadClientResults(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(timeout = 5)
    public boolean link(UUID client, UUID token, UUID participant) {
        return jdbc.update("""
                update load_test_clients c set participant_id=p.id from purchase_participants p
                where c.id=? and c.lease_token=? and c.status='RUNNING' and c.lease_expires_at>clock_timestamp()
                    and p.id=? and p.user_id=c.id and p.run_id=c.run_id and p.concert_id=c.concert_id
                    and p.preferred_seat_id=c.seat_id and p.source='TEST'
                    and p.first_name=c.first_name and p.last_name=c.last_name
                    and (c.participant_id is null or c.participant_id=p.id)
                """, client, token, participant) == 1;
    }

    @Transactional(timeout = 5)
    public boolean finish(UUID client, UUID token, Outcome outcome, Failure failure) {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == Outcome.FAILED) != (failure != null)) {
            throw new IllegalArgumentException("Only failed clients require a failure code");
        }
        return jdbc.update("""
                update load_test_clients c set status=?,error_code=?,finished_at=clock_timestamp(),
                    lease_token=null,lease_expires_at=null
                where c.id=? and c.lease_token=? and c.status='RUNNING' and c.lease_expires_at>clock_timestamp()
                    and (? <> 'SUCCEEDED' or exists (
                        select 1 from purchase_participants p join reservations r on r.id=p.reservation_id
                        join payments pay on pay.reservation_id=r.id
                        where p.id=c.participant_id and p.user_id=c.id and p.run_id=c.run_id
                            and p.concert_id=c.concert_id and p.preferred_seat_id=c.seat_id
                            and r.status='CONFIRMED' and pay.status='SUCCESS'))
                """, outcome.name(), failure == null ? null : failure.name(), client, token, outcome.name()) == 1;
    }

    public enum Outcome {
        SUCCEEDED, SOLD_OUT, FAILED, CANCELLED
    }

    public enum Failure {
        HTTP_ERROR, QUEUE_TIMEOUT, ADMISSION_REJECTED, PURCHASE_REJECTED, WORKER_ERROR
    }
}
