package com.lazorenkoo.eventrush.crowdgate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "eventrush.crowdgate.enabled", havingValue = "true")
public class CrowdGateClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI redeemUri;
    private final Duration timeout;

    public CrowdGateClient(ObjectMapper mapper,
            @Value("${eventrush.crowdgate.base-url}") URI origin,
            @Value("${eventrush.crowdgate.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${eventrush.crowdgate.request-timeout:PT5S}") Duration timeout) {
        if (!("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) {
            throw new IllegalArgumentException("CrowdGate base URL must be an HTTP origin");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("CrowdGate request timeout must be positive");
        }
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.mapper = mapper;
        this.redeemUri = origin.resolve("/api/v1/tokens/redeem");
        this.timeout = timeout;
    }

    public Admission redeem(UUID expectedGate, UUID visitor, String token) {
        Objects.requireNonNull(expectedGate, "expectedGate");
        Objects.requireNonNull(visitor, "visitor");
        if (token == null || token.isBlank() || token.length() > 8192) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ADMISSION_REQUIRED");
        }
        try {
            var request = HttpRequest.newBuilder(redeemUri).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Cookie", "cg_user_id=" + visitor)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("admissionToken", token))))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                HttpStatus status = switch (response.statusCode()) {
                    case 400, 401, 403, 404, 409, 410 -> HttpStatus.FORBIDDEN;
                    default -> HttpStatus.SERVICE_UNAVAILABLE;
                };
                throw new ResponseStatusException(status, "CROWDGATE_ADMISSION_REJECTED");
            }
            var body = mapper.readTree(response.body());
            if (body == null || !body.isObject()) {
                throw new IllegalArgumentException("Invalid CrowdGate response");
            }
            String[] queue = body.path("queueToken").asText().split(":", -1);
            if (!"ACTIVE".equals(body.path("status").asText()) || queue.length != 2
                    || !expectedGate.toString().equals(queue[0])) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CROWDGATE_GATE_MISMATCH");
            }
            UUID queueSession = UUID.fromString(queue[1]);
            var sessions = response.headers().allValues("Set-Cookie").stream()
                    .flatMap(value -> HttpCookie.parse(value).stream())
                    .filter(cookie -> "cg_session".equals(cookie.getName()) && cookie.getMaxAge() > 0)
                    .toList();
            if (sessions.size() != 1 || !sessions.getFirst().getValue().matches("[A-Za-z0-9:_-]{1,512}")) {
                throw new IllegalArgumentException("Invalid CrowdGate session");
            }
            return new Admission(expectedGate, visitor, queueSession, sessions.getFirst().getValue());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CROWDGATE_UNAVAILABLE");
        } catch (IOException | IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CROWDGATE_UNAVAILABLE");
        }
    }

    public Instant validateSession(Admission admission) {
        return session(admission, false);
    }

    public Instant heartbeat(Admission admission) {
        return session(admission, true);
    }

    private Instant session(Admission admission, boolean heartbeat) {
        Objects.requireNonNull(admission, "admission");
        String credential = admission.sessionCredential();
        if (admission.gateId() == null || admission.visitorId() == null || credential == null
                || !credential.matches(admission.gateId() + ":[A-Za-z0-9_-]{43}")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "CROWDGATE_SESSION_REQUIRED");
        }
        try {
            String path = "/api/v1/gates/" + admission.gateId() + "/session" + (heartbeat ? "/heartbeat" : "");
            var builder = HttpRequest.newBuilder(redeemUri.resolve(path)).timeout(timeout)
                    .header("Cookie", "cg_user_id=" + admission.visitorId() + "; cg_session=" + credential);
            var request = heartbeat ? builder.POST(HttpRequest.BodyPublishers.noBody()).build() : builder.GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                HttpStatus status = switch (response.statusCode()) {
                    case 400, 401, 403, 404, 409, 410 -> HttpStatus.UNAUTHORIZED;
                    default -> HttpStatus.SERVICE_UNAVAILABLE;
                };
                throw new ResponseStatusException(status, "CROWDGATE_SESSION_REJECTED");
            }
            var body = mapper.readTree(response.body());
            if (body == null || !admission.gateId().toString().equals(body.path("gateId").asText())
                    || !body.path("expiresAtMillis").canConvertToLong()) {
                throw new IllegalArgumentException("Invalid CrowdGate session response");
            }
            Instant expiresAt = Instant.ofEpochMilli(body.path("expiresAtMillis").longValue());
            if (!expiresAt.isAfter(Instant.now())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "CROWDGATE_SESSION_EXPIRED");
            }
            return expiresAt;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CROWDGATE_UNAVAILABLE");
        } catch (IOException | IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CROWDGATE_UNAVAILABLE");
        }
    }

    public record Admission(UUID gateId, UUID visitorId, UUID queueSessionId, String sessionCredential) {
        @Override
        public String toString() {
            return "Admission[gateId=" + gateId + ", visitorId=" + visitorId + ", queueSessionId=" + queueSessionId + "]";
        }
    }
}
