package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.load.LoadRunLifecycleService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/concerts/{concertId}/load-runs/{runId}")
public class LoadRunControlController {
    private final JdbcTemplate jdbc;
    private final LoadRunLifecycleService lifecycle;

    public LoadRunControlController(JdbcTemplate jdbc, LoadRunLifecycleService lifecycle) {
        this.jdbc = jdbc;
        this.lifecycle = lifecycle;
    }

    @PostMapping("/stop")
    public ResponseEntity<State> stop(@PathVariable UUID concertId, @PathVariable UUID runId) {
        String state = lifecycle.stop(concertId, runId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new State(runId, state));
    }

    @GetMapping("/progress")
    public ResponseEntity<Progress> progress(@PathVariable UUID concertId, @PathVariable UUID runId) {
        // One statement gives run state and client counters from the same database snapshot.
        var progress = jdbc.query("""
                select r.id,r.concert_id,r.status,r.participant_count,r.concurrency,
                    count(c.id) filter (where c.status='PENDING') as pending,
                    count(c.id) filter (where c.status='RUNNING') as running,
                    count(c.id) filter (where c.status='SUCCEEDED') as succeeded,
                    count(c.id) filter (where c.status='SOLD_OUT') as unavailable,
                    count(c.id) filter (where c.status='FAILED') as failed,
                    count(c.id) filter (where c.status='CANCELLED') as cancelled
                from load_test_runs r left join load_test_clients c on c.run_id=r.id
                where r.id=? and r.concert_id=? group by r.id
                """, (row, index) -> new Progress(row.getObject("id", UUID.class),
                row.getObject("concert_id", UUID.class), "TEST_RUN", row.getString("status"),
                row.getInt("participant_count"), row.getInt("concurrency"), row.getLong("pending"),
                row.getLong("running"), row.getLong("succeeded"), row.getLong("unavailable"),
                row.getLong("failed"), row.getLong("cancelled")), runId, concertId)
                .stream().findFirst().orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "LOAD_RUN_NOT_FOUND"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progress);
    }

    public record State(UUID runId, String status) {
    }

    public record Progress(UUID runId, UUID concertId, String scope, String status, int participantCount,
                           int concurrency, long pending, long running, long succeeded, long unavailable,
                           long failed, long cancelled) {
    }
}
