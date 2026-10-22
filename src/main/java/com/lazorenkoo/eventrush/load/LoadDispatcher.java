package com.lazorenkoo.eventrush.load;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = {"eventrush.load.enabled", "eventrush.crowdgate.enabled"}, havingValue = "true")
public class LoadDispatcher {
    private static final Logger log = LoggerFactory.getLogger(LoadDispatcher.class);
    private final JdbcTemplate jdbc;
    private final LoadClientLeases leases;
    private final LoadBuyerWorker worker;
    private final ConcurrentHashMap<UUID, Task> tasks = new ConcurrentHashMap<>();
    private boolean closed;

    public LoadDispatcher(JdbcTemplate jdbc, LoadClientLeases leases, LoadBuyerWorker worker) {
        this.jdbc = jdbc;
        this.leases = leases;
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${eventrush.load.dispatch-delay:1000}")
    public synchronized void tick() {
        if (closed) return;
        try {
            var runs = jdbc.query("""
                    select id,status from load_test_runs where status in ('RUNNING','STOPPING') limit 1
                    """, (row, index) -> new Run(row.getObject("id", UUID.class), row.getString("status")));
            for (Run run : runs) {
                if (run.status().equals("STOPPING")) {
                    tasks.values().stream().filter(task -> task.run().equals(run.id()))
                            .forEach(task -> task.thread().interrupt());
                }
                leases.reap(run.id());
                int capacity = 100 - tasks.size();
                if (!run.status().equals("RUNNING") || capacity <= 0) continue;
                for (var client : leases.claim(run.id(), capacity)) start(client);
            }
        } catch (RuntimeException exception) {
            log.warn("Load dispatch deferred: {}", exception.getClass().getSimpleName());
        }
    }

    private void start(LoadClientLeases.Client client) {
        Thread thread = Thread.ofVirtual().name("load-buyer").unstarted(() -> {
            try {
                worker.execute(client);
            } finally {
                // Retain the slot until the worker really exits, including cancellation cleanup.
                tasks.remove(client.id());
            }
        });
        if (tasks.putIfAbsent(client.id(), new Task(client.runId(), thread)) != null) return;
        try {
            thread.start();
        } catch (RuntimeException exception) {
            tasks.remove(client.id());
            throw exception;
        }
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        tasks.values().forEach(task -> task.thread().interrupt());
    }

    private record Run(UUID id, String status) {
    }

    private record Task(UUID run, Thread thread) {
    }
}
