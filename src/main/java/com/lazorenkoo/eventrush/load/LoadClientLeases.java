package com.lazorenkoo.eventrush.load;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class LoadClientLeases {
    private final JdbcTemplate jdbc;

    public LoadClientLeases(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(timeout = 5)
    public List<Client> claim(UUID run) {
        return claim(run, 100);
    }

    @Transactional(timeout = 5)
    public List<Client> claim(UUID run, int capacity) {
        if (capacity < 1 || capacity > 100) throw new IllegalArgumentException("Invalid worker capacity");
        var settings = jdbc.query("select status,concurrency from load_test_runs where id=? for update",
                (row, index) -> new Settings(row.getString("status"), row.getInt("concurrency")), run);
        if (settings.isEmpty() || !settings.getFirst().status().equals("RUNNING")) return List.of();
        expire(run);
        int active = jdbc.queryForObject("select count(*) from load_test_clients where run_id=? and status='RUNNING'",
                Integer.class, run);
        int available = settings.getFirst().concurrency() - active;
        if (available <= 0) return List.of();
        return jdbc.query("""
                update load_test_clients set status='RUNNING',lease_token=gen_random_uuid(),
                    lease_expires_at=clock_timestamp()+interval '60 seconds'
                where id in (select id from load_test_clients where run_id=? and status='PENDING'
                    order by ordinal limit ? for update skip locked)
                returning id,run_id,concert_id,seat_id,first_name,last_name,lease_token
                """, (row, index) -> new Client(row.getObject("id", UUID.class), row.getObject("run_id", UUID.class),
                row.getObject("concert_id", UUID.class), row.getObject("seat_id", UUID.class),
                row.getString("first_name"), row.getString("last_name"), row.getObject("lease_token", UUID.class)),
                run, Math.min(available, capacity));
    }

    @Transactional(timeout = 5)
    public boolean renew(UUID client, UUID token) {
        return jdbc.update("""
                update load_test_clients set lease_expires_at=clock_timestamp()+interval '60 seconds'
                where id=? and lease_token=? and status='RUNNING' and lease_expires_at>clock_timestamp()
                """, client, token) == 1;
    }

    @Transactional(timeout = 5)
    public void reap(UUID run) {
        var states = jdbc.queryForList("select status from load_test_runs where id=? for update", String.class, run);
        if (states.isEmpty() || !List.of("RUNNING", "STOPPING").contains(states.getFirst())) return;
        expire(run);
        boolean stopping = states.getFirst().equals("STOPPING");
        if (stopping) jdbc.update("""
                update load_test_clients set status='CANCELLED',finished_at=clock_timestamp()
                where run_id=? and status='PENDING'
                """, run);
        jdbc.update("""
                update load_test_runs set status=?,finished_at=clock_timestamp() where id=?
                    and not exists (select 1 from load_test_clients where run_id=? and status in ('PENDING','RUNNING'))
                """, stopping ? "STOPPED" : "COMPLETED", run, run);
    }

    private void expire(UUID run) {
        // An interrupted HTTP client is failed, never replayed as a fresh buyer.
        jdbc.update("""
                update load_test_clients set status='FAILED',finished_at=clock_timestamp(),
                    error_code='WORKER_LEASE_EXPIRED',lease_token=null,lease_expires_at=null
                where run_id=? and status='RUNNING' and lease_expires_at<=clock_timestamp()
                """, run);
    }

    public record Client(UUID id, UUID runId, UUID concertId, UUID seatId,
                         String firstName, String lastName, UUID leaseToken) {
        @Override
        public String toString() {
            return "Client[redacted]";
        }
    }

    private record Settings(String status, int concurrency) {
    }
}
