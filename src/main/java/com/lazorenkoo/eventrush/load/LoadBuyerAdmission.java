package com.lazorenkoo.eventrush.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "eventrush.crowdgate.enabled", havingValue = "true")
public class LoadBuyerAdmission {
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final LoadClientLeases leases;
    private final LoadParticipantRegistration registration;

    public LoadBuyerAdmission(ObjectMapper mapper, JdbcTemplate jdbc, LoadClientLeases leases,
            LoadParticipantRegistration registration) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.leases = leases;
        this.registration = registration;
    }

    public UUID admit(LoadClientLeases.Client client, LoadBuyerHttp.Visit visit)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
        checkpoint(client, deadline);
        JsonNode concert = request(visit, "GET", "/api/v1/concerts/" + client.concertId(), null, 200);
        if (!client.concertId().toString().equals(text(concert, "id"))) throw invalid();
        UUID gate = canonicalUuid(text(concert, "crowdgateGateId"));
        checkpoint(client, deadline);
        JsonNode entry = request(visit, "POST", "/api/v1/gates/" + gate + "/join", null, 200);
        String token = text(entry, "queueToken");
        if (!token.startsWith(gate + ":")) throw invalid();
        canonicalUuid(token.substring(gate.toString().length() + 1));
        String path = "/api/v1/queue/" + token;
        while (true) {
            checkpoint(client, deadline);
            if (!token.equals(text(entry, "queueToken"))) throw invalid();
            String state = text(entry, "status");
            if (!state.equals("WAITING") && !state.equals("ADMITTED")) throw invalid();
            // Registration verifies queue ownership with CrowdGate and records the observed state.
            UUID participant = registration.register(client.id(), client.leaseToken(), token);
            if (state.equals("ADMITTED")) {
                checkpoint(client, deadline);
                JsonNode issued = request(visit, "POST", path + "/admission-token", null, 200);
                String admission = text(issued, "admissionToken");
                if (admission.length() > 8192) throw invalid();
                checkpoint(client, deadline);
                request(visit, "POST", "/api/v1/admission/exchange",
                        Map.of("concertId", client.concertId(), "admissionToken", admission), 204);
                checkpoint(client, deadline);
                var seats = visit.send("GET", "/api/v1/concerts/" + client.concertId() + "/seats?limit=1", null);
                if (seats.statusCode() != 200) throw new IOException("Admission session unavailable");
                return participant;
            }
            Thread.sleep(1000);
            checkpoint(client, deadline);
            entry = request(visit, "GET", path, null, 200);
        }
    }

    private void checkpoint(LoadClientLeases.Client client, long deadline) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Buyer interrupted");
        if (System.nanoTime() - deadline >= 0) throw new HttpTimeoutException("Queue wait timed out");
        boolean running = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from load_test_runs where id=? and status='RUNNING')",
                Boolean.class, client.runId()));
        if (!running || !leases.renew(client.id(), client.leaseToken())) {
            throw new InterruptedException("Load client stopped");
        }
    }

    private JsonNode request(LoadBuyerHttp.Visit visit, String method, String path, Object body, int expected)
            throws IOException, InterruptedException {
        var response = visit.send(method, path, body);
        if (response.statusCode() != expected) throw new IOException("Admission HTTP request failed");
        if (expected == 204) return mapper.nullNode();
        JsonNode value = mapper.readTree(response.body());
        if (value == null || !value.isObject()) throw invalid();
        return value;
    }

    private String text(JsonNode value, String field) throws IOException {
        JsonNode item = value.get(field);
        if (item == null || !item.isTextual() || item.textValue().isBlank()) throw invalid();
        return item.textValue();
    }

    private UUID canonicalUuid(String value) throws IOException {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equals(value)) return id;
        } catch (IllegalArgumentException ignored) {
            // Reject malformed identities before constructing another HTTP path.
        }
        throw invalid();
    }

    private IOException invalid() {
        return new IOException("Invalid admission response");
    }
}
