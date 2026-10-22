package com.lazorenkoo.eventrush.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class LoadBuyerPurchase {
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final LoadClientLeases leases;

    public LoadBuyerPurchase(ObjectMapper mapper, JdbcTemplate jdbc, LoadClientLeases leases) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.leases = leases;
    }

    public LoadClientResults.Outcome purchase(LoadClientLeases.Client client, UUID participant,
            LoadBuyerHttp.Visit visit) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        checkpoint(client, deadline);
        var reserved = visit.send("POST", "/api/v1/seats/" + client.seatId()
                + "/reserve?participantId=" + participant, null);
        if (reserved.statusCode() == 409 && "SEAT_ALREADY_RESERVED".equals(json(reserved).path("code").asText())) {
            return LoadClientResults.Outcome.SOLD_OUT;
        }
        JsonNode reservation = successful(reserved, 201);
        UUID id = identity(reservation, "id");
        requireSeat(reservation, client.seatId());
        if (!"ACTIVE".equals(reservation.path("status").asText())) throw invalid();
        String path = "/api/v1/reservations/" + id;
        long heartbeat = System.nanoTime();
        UUID payment = null;
        while (true) {
            checkpoint(client, deadline);
            if (System.nanoTime() - heartbeat >= Duration.ofSeconds(30).toNanos()) {
                successful(visit.send("POST", "/api/v1/admission/heartbeat", null), 200);
                heartbeat = System.nanoTime();
                checkpoint(client, deadline);
            }
            // Repeating the same mode reads the existing payment; it never starts a second charge.
            var response = visit.send("POST", path + "/pay", Map.of("mode", "SUCCESS"));
            JsonNode paid = successful(response, 200, 202);
            UUID current = identity(paid, "id");
            if (!id.equals(identity(paid, "reservationId")) || (payment != null && !payment.equals(current))) {
                throw invalid();
            }
            payment = current;
            String state = paid.path("status").asText();
            if (!state.equals("PENDING") && !state.equals("SUCCESS")) throw new IOException("Payment rejected");
            checkpoint(client, deadline);
            reservation = successful(visit.send("GET", path, null), 200);
            if (!id.equals(identity(reservation, "id"))) throw invalid();
            requireSeat(reservation, client.seatId());
            String status = reservation.path("status").asText();
            if (status.equals("CONFIRMED") && state.equals("SUCCESS")) return LoadClientResults.Outcome.SUCCEEDED;
            if (!status.equals("ACTIVE") && !status.equals("CONFIRMED")) throw new IOException("Reservation ended");
            Thread.sleep(1000);
        }
    }

    private void checkpoint(LoadClientLeases.Client client, long deadline) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Buyer interrupted");
        if (System.nanoTime() - deadline >= 0) throw new HttpTimeoutException("Purchase timed out");
        boolean running = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from load_test_runs where id=? and status='RUNNING')",
                Boolean.class, client.runId()));
        if (!running || !leases.renew(client.id(), client.leaseToken())) throw new InterruptedException("Buyer stopped");
    }

    private JsonNode successful(HttpResponse<byte[]> response, int... statuses) throws IOException {
        for (int status : statuses) if (response.statusCode() == status) return json(response);
        throw new IOException("Purchase HTTP request failed");
    }

    private JsonNode json(HttpResponse<byte[]> response) throws IOException {
        JsonNode value = mapper.readTree(response.body());
        if (value == null || !value.isObject()) throw invalid();
        return value;
    }

    private UUID identity(JsonNode value, String field) throws IOException {
        String text = value.path(field).asText();
        try {
            UUID id = UUID.fromString(text);
            if (id.toString().equals(text)) return id;
        } catch (IllegalArgumentException ignored) {
            // Never construct a follow-up path from an unvalidated response identity.
        }
        throw invalid();
    }

    private void requireSeat(JsonNode value, UUID seat) throws IOException {
        if (!seat.equals(identity(value, "seatId"))) throw invalid();
    }

    private IOException invalid() {
        return new IOException("Invalid purchase response");
    }
}
