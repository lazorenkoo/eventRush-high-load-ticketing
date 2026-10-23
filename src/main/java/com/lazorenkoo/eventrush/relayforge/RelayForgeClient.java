package com.lazorenkoo.eventrush.relayforge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "eventrush.relayforge.enabled", havingValue = "true")
public class RelayForgeClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI eventsUri;
    private final String apiKey;
    private final Duration requestTimeout;

    public RelayForgeClient(ObjectMapper mapper,
            @Value("${eventrush.relayforge.base-url}") URI baseUrl,
            @Value("${eventrush.relayforge.api-key}") String apiKey,
            @Value("${eventrush.relayforge.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${eventrush.relayforge.request-timeout:PT5S}") Duration requestTimeout) {
        if (!("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath()))) {
            throw new IllegalArgumentException("RelayForge base URL must be an HTTP origin");
        }
        if (apiKey.isBlank() || apiKey.contains("\r") || apiKey.contains("\n")) {
            throw new IllegalArgumentException("RelayForge API key is required");
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("RelayForge request timeout must be positive");
        }
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.mapper = mapper;
        this.eventsUri = baseUrl.resolve("/api/v1/events");
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
    }

    public long publishedId(UUID eventId, String type, String payload) {
        Objects.requireNonNull(eventId, "eventId");
        try {
            String body = mapper.writeValueAsString(Map.of("type", type, "payload", payload));
            var request = HttpRequest.newBuilder(eventsUri).timeout(requestTimeout)
                    .header("Content-Type", "application/json").header("X-API-Key", apiKey)
                    .header("Idempotency-Key", eventId.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            JsonNode value = json(request).path("id");
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
                throw new IllegalStateException("Invalid RelayForge event identity");
            }
            return value.longValue();
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("RelayForge event cannot be serialized");
        }
    }

    public List<DeliveryView> deliveries(long eventId) {
        if (eventId <= 0) throw new IllegalArgumentException("Event identity must be positive");
        var uri = eventsUri.resolve("/api/v1/deliveries?eventId=" + eventId);
        var request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                .header("X-API-Key", apiKey).GET().build();
        JsonNode rows = json(request);
        if (!rows.isArray() || rows.size() > 100) throw invalidDelivery();
        var result = new ArrayList<DeliveryView>();
        var identities = new java.util.HashSet<Long>();
        for (JsonNode row : rows) {
            long id = positiveId(row.path("id"));
            if (!identities.add(id) || positiveId(row.path("eventId")) != eventId) {
                throw invalidDelivery();
            }
            JsonNode status = row.path("status");
            if (!status.isTextual() || !Set.of("PENDING", "PROCESSING", "SUCCEEDED", "FAILED")
                    .contains(status.textValue())) throw invalidDelivery();
            JsonNode attempts = row.path("attemptCount");
            if (!attempts.isIntegralNumber() || !attempts.canConvertToInt() || attempts.intValue() < 0) {
                throw invalidDelivery();
            }
            JsonNode httpStatus = row.path("httpStatus");
            Integer code = null;
            if (!httpStatus.isNull()) {
                if (!httpStatus.isIntegralNumber() || !httpStatus.canConvertToInt()
                        || httpStatus.intValue() < 100 || httpStatus.intValue() > 599) {
                    throw invalidDelivery();
                }
                code = httpStatus.intValue();
            }
            result.add(new DeliveryView(id, eventId, positiveId(row.path("endpointId")),
                    status.textValue(), code, attempts.intValue(),
                    deliveryTime(row.path("nextAttemptAt")), deliveryTime(row.path("completedAt"))));
        }
        return List.copyOf(result);
    }

    public List<AttemptView> attempts(long deliveryId) {
        if (deliveryId <= 0) throw new IllegalArgumentException("Delivery identity must be positive");
        var uri = eventsUri.resolve("/api/v1/deliveries/" + deliveryId + "/attempts");
        var request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                .header("X-API-Key", apiKey).GET().build();
        JsonNode rows = json(request);
        if (!rows.isArray() || rows.size() > 100) throw invalidDelivery();
        var result = new ArrayList<AttemptView>();
        var identities = new java.util.HashSet<Long>();
        var numbers = new java.util.HashSet<Integer>();
        for (JsonNode row : rows) {
            long id = positiveId(row.path("id"));
            if (!identities.add(id) || positiveId(row.path("deliveryId")) != deliveryId) {
                throw invalidDelivery();
            }
            JsonNode number = row.path("attemptNumber");
            if (!number.isIntegralNumber() || !number.canConvertToInt() || number.intValue() < 1
                    || !numbers.add(number.intValue())) throw invalidDelivery();
            JsonNode status = row.path("status");
            if (!status.isTextual() || !Set.of("PENDING", "PROCESSING", "SUCCEEDED", "FAILED")
                    .contains(status.textValue())) throw invalidDelivery();
            JsonNode httpStatus = row.path("httpStatus");
            Integer code = null;
            if (!httpStatus.isNull()) {
                if (!httpStatus.isIntegralNumber() || !httpStatus.canConvertToInt()
                        || httpStatus.intValue() < 100 || httpStatus.intValue() > 599) {
                    throw invalidDelivery();
                }
                code = httpStatus.intValue();
            }
            result.add(new AttemptView(id, deliveryId, number.intValue(), code,
                    deliveryTime(row.path("completedAt")), status.textValue()));
        }
        return List.copyOf(result);
    }

    public record AttemptView(long id, long deliveryId, int attemptNumber, Integer httpStatus,
                              Instant completedAt, String status) {}
    private static long positiveId(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw invalidDelivery();
        }
        return value.longValue();
    }

    private static Instant deliveryTime(JsonNode value) {
        if (value.isNull()) return null;
        if (!value.isTextual() || value.textValue().length() > 40) throw invalidDelivery();
        try {
            return Instant.parse(value.textValue());
        } catch (DateTimeParseException error) {
            throw invalidDelivery();
        }
    }

    private static IllegalStateException invalidDelivery() {
        return new IllegalStateException("Invalid RelayForge delivery response");
    }

    public record DeliveryView(long id, long eventId, long endpointId, String status,
                               Integer httpStatus, int attemptCount,
                               Instant nextAttemptAt, Instant completedAt) {}

    public RetryResult retry(long deliveryId) {
        if (deliveryId <= 0) throw new IllegalArgumentException("Delivery identity must be positive");
        var uri = eventsUri.resolve("/api/v1/deliveries/" + deliveryId + "/retry");
        var request = HttpRequest.newBuilder(uri).timeout(requestTimeout).header("X-API-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        JsonNode response = json(request);
        JsonNode code = response.path("httpStatus");
        JsonNode successful = response.path("successful");
        if (!code.isIntegralNumber() || !code.canConvertToInt() || code.intValue() < 100
                || code.intValue() > 599 || !successful.isBoolean()
                || successful.booleanValue() != (code.intValue() >= 200 && code.intValue() < 300)) {
            throw new IllegalStateException("Invalid RelayForge retry response");
        }
        return new RetryResult(code.intValue(), successful.booleanValue());
    }

    public record RetryResult(int httpStatus, boolean successful) {}

    public static final class RemoteConflict extends IllegalStateException {
        private RemoteConflict() {
            super("RelayForge returned HTTP 409");
        }
    }
    private JsonNode json(HttpRequest request) {
        var pending = http.sendAsync(request, ignored -> new BoundedResponseBody());
        try {
            var response = pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() == 409) throw new RemoteConflict();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("RelayForge returned HTTP " + response.statusCode());
            }
            JsonNode value = mapper.readTree(response.body());
            if (value == null) throw new IllegalStateException("Invalid RelayForge response");
            return value;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RelayForge request interrupted");
        } catch (IOException | ExecutionException | TimeoutException error) {
            throw new IllegalStateException("RelayForge request failed");
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    public void publish(UUID eventId, String type, String payload) {
        Objects.requireNonNull(eventId, "eventId");
        try {
            String body = mapper.writeValueAsString(Map.of("type", type, "payload", payload));
            var request = HttpRequest.newBuilder(eventsUri).timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", apiKey)
                    .header("Idempotency-Key", eventId.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("RelayForge returned HTTP " + status);
            }
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("RelayForge event cannot be serialized");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RelayForge request interrupted");
        } catch (IOException error) {
            throw new IllegalStateException("RelayForge request failed");
        }
    }
}
