package com.lazorenkoo.eventrush.relayforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayForgeClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private URI origin;
    private volatile int responseStatus = 201;
    private volatile boolean holdResponse;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/events", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("X-API-Key"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            try {
                if (holdResponse) {
                    release.await(3, TimeUnit.SECONDS);
                }
                exchange.getResponseHeaders().set("Location", origin + "/redirected");
                exchange.sendResponseHeaders(responseStatus, -1);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void preservesPayloadTextAndStableIdentityAcrossRepeatedRequests() throws Exception {
        UUID id = UUID.randomUUID();
        String payload = "{ \"message\": \"Привет\", \"amount\": 10.00 }";
        var client = client(Duration.ofSeconds(2));
        client.publish(id, "ticket.purchased", payload);
        client.publish(id, "ticket.purchased", payload);
        assertThat(requests).hasSize(2);
        for (Request request : requests) {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.apiKey()).isEqualTo("test-api-key");
            assertThat(request.idempotencyKey()).isEqualTo(id.toString());
            assertThat(request.contentType()).isEqualTo("application/json");
            var body = mapper.readTree(request.body());
            assertThat(body.get("type").asText()).isEqualTo("ticket.purchased");
            assertThat(body.get("payload").isTextual()).isTrue();
            assertThat(body.get("payload").asText()).isEqualTo(payload);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 401, 409, 503})
    void rejectsErrorsAndRedirectsWithoutFollowingThem(int status) {
        responseStatus = status;
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).publish(UUID.randomUUID(), "ticket.reserved", "{}"))
                .isInstanceOf(IllegalStateException.class).hasMessage("RelayForge returned HTTP " + status);
        assertThat(requests).hasSize(1);
    }

    @Test
    void requestDeadlineFailsWhenReceiverDoesNotRespond() {
        holdResponse = true;
        assertThatThrownBy(() -> client(Duration.ofMillis(100)).publish(UUID.randomUUID(), "ticket.reserved", "{}"))
                .isInstanceOf(IllegalStateException.class).hasMessage("RelayForge request failed");
    }

    private RelayForgeClient client(Duration timeout) {
        return new RelayForgeClient(mapper, origin, "test-api-key", Duration.ofSeconds(1), timeout);
    }

    private record Request(String method, String apiKey, String idempotencyKey, String contentType, String body) {
    }
}
