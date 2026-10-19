package com.lazorenkoo.eventrush.relayforge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
