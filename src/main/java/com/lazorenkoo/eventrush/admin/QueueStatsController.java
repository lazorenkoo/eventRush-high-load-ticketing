package com.lazorenkoo.eventrush.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "eventrush.crowdgate.enabled", havingValue = "true")
public class QueueStatsController {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;
    private final String key;

    public QueueStatsController(@Value("${eventrush.crowdgate.base-url}") URI origin,
            @Value("${eventrush.crowdgate.admin-key:}") String key) {
        if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                || origin.getFragment() != null || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                || !(origin.getScheme().equals("http") || origin.getScheme().equals("https"))) {
            throw new IllegalArgumentException("CrowdGate base URL must be an HTTP origin");
        }
        this.endpoint = origin.resolve("/actuator/prometheus");
        this.key = key;
    }

    @GetMapping("/api/v1/admin/queue-stats")
    public ResponseEntity<QueueStats> read() {
        if (key.isBlank()) throw unavailable();
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                .header("X-Admin-Key", key).GET().build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                byte[] bytes = body.readNBytes(131073);
                if (response.statusCode() != 200 || bytes.length > 131072) throw unavailable();
                Map<String, Double> values = new HashMap<>();
                for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length == 2 && parts[0].startsWith("crowdgate_")) {
                        values.put(parts[0], Double.parseDouble(parts[1]));
                    }
                }
                double age = require(values, "crowdgate_metrics_snapshot_age_seconds");
                if (age > 30) throw unavailable();
                var result = new QueueStats("ALL_GATES", Instant.now(), age,
                        count(values, "crowdgate_queue_size"), count(values, "crowdgate_reserved_slots"),
                        count(values, "crowdgate_active_sessions"));
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | IllegalArgumentException error) {
            throw unavailable();
        }
    }

    private static double require(Map<String, Double> values, String name) {
        Double value = values.get(name);
        if (value == null || !Double.isFinite(value) || value < 0) throw unavailable();
        return value;
    }

    private static long count(Map<String, Double> values, String name) {
        double value = require(values, name);
        if (value != Math.rint(value) || value >= Long.MAX_VALUE) throw unavailable();
        return (long) value;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_STATS_UNAVAILABLE");
    }

    public record QueueStats(String scope, Instant fetchedAt, double snapshotAgeSeconds,
                             long waiting, long admitted, long active) {
    }
}
