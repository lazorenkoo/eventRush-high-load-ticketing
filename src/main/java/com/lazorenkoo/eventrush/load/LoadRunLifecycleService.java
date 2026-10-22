package com.lazorenkoo.eventrush.load;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class LoadRunLifecycleService {
    private final JdbcTemplate jdbc;

    public LoadRunLifecycleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(timeout = 10)
    public String start(UUID concert, UUID run) {
        String status = lock(concert, run);
        if (status.equals("RUNNING")) return status;
        if (!status.equals("READY")) throw conflict("LOAD_RUN_NOT_READY");
        Boolean onSale = jdbc.queryForObject("""
                select status='ON_SALE' and sale_starts_at <= clock_timestamp()
                from concerts where id=? for share
                """, Boolean.class, concert);
        if (!Boolean.TRUE.equals(onSale)) throw conflict("CONCERT_NOT_ON_SALE");
        Boolean complete = jdbc.queryForObject("""
                select participant_count = (select count(*) from load_test_clients
                    where run_id=r.id and status='PENDING' and participant_id is null)
                from load_test_runs r where id=?
                """, Boolean.class, run);
        if (!Boolean.TRUE.equals(complete)) throw conflict("LOAD_RUN_NOT_READY");
        try {
            jdbc.update("update load_test_runs set status='RUNNING',started_at=clock_timestamp() where id=?", run);
        } catch (DuplicateKeyException exception) {
            throw conflict("LOAD_RUN_ALREADY_ACTIVE");
        }
        return "RUNNING";
    }

    @Transactional(timeout = 10)
    public String stop(UUID concert, UUID run) {
        String status = lock(concert, run);
        if (status.equals("READY")) {
            jdbc.update("""
                    update load_test_clients set status='CANCELLED',finished_at=clock_timestamp()
                    where run_id=? and status='PENDING'
                    """, run);
            jdbc.update("update load_test_runs set status='STOPPED',finished_at=clock_timestamp() where id=?", run);
            return "STOPPED";
        }
        if (status.equals("RUNNING")) {
            jdbc.update("update load_test_runs set status='STOPPING' where id=?", run);
            return "STOPPING";
        }
        return status;
    }

    private String lock(UUID concert, UUID run) {
        return jdbc.queryForList("select status from load_test_runs where id=? and concert_id=? for update",
                String.class, run, concert).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "LOAD_RUN_NOT_FOUND"));
    }

    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}