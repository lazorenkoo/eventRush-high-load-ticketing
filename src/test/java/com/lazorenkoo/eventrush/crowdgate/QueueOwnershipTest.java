package com.lazorenkoo.eventrush.crowdgate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueOwnershipTest {
    private final UUID gate = UUID.randomUUID();
    private final UUID visitor = UUID.randomUUID();
    private final String token = gate + ":" + UUID.randomUUID();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private CrowdGateClient client;
    private String response;
    private int status = 200;
    private String method;
    private String path;

    @BeforeEach
    void start() throws Exception {
        response = body(token, "WAITING");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/queue/", exchange -> {
            requests.incrementAndGet();
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            int code = ("cg_user_id=" + visitor).equals(exchange.getRequestHeaders().getFirst("Cookie"))
                    ? status : 403;
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
            exchange.close();
        });
        server.start();
        client = new CrowdGateClient(new ObjectMapper(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @AfterEach
    void stop() { server.stop(0); }

    private String body(String queue, String state) {
        return "{\"queueToken\":\"" + queue + "\",\"status\":\"" + state + "\"}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING", "ADMITTED", "ACTIVE"})
    void verifiesLiveQueueOwnership(String state) {
        response = body(token, state);
        assertThat(client.requireQueueOwner(gate, visitor, token)).isEqualTo(state);
        assertThat(method).isEqualTo("GET");
        assertThat(path).isEqualTo("/api/v1/queue/" + token);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidTokensBeforeAnyNetworkRequest() {
        for (String value : new String[]{null, "", "../admin", UUID.randomUUID() + ":" + UUID.randomUUID(),
                gate + ":00000000-0000-0000-0000-00000000000G", token + "\r\n"}) {
            rejected(() -> client.requireQueueOwner(gate, visitor, value), 401);
        }
        assertThat(requests.get()).isZero();
    }

    @Test
    void anotherVisitorCannotClaimQueueEntry() {
        rejected(() -> client.requireQueueOwner(gate, UUID.randomUUID(), token), 401);
        assertThat(requests.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 410, 302, 500, 503})
    void mapsUpstreamRejectionsWithoutLeakingResponse(int code) {
        status = code;
        rejected(() -> client.requireQueueOwner(gate, visitor, token), code < 500 && code != 302 ? 401 : 503);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "CANCELLED", "", "UNKNOWN"})
    void rejectsEndedOrUnknownStates(String state) {
        response = body(token, state);
        rejected(() -> client.requireQueueOwner(gate, visitor, token), 401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "null", "{}", "[]"})
    void rejectsMalformedOrMissingQueueProof(String value) {
        response = value;
        rejected(() -> client.requireQueueOwner(gate, visitor, token), 503);
    }

    @Test
    void rejectsAnotherQueueTokenInSuccessfulResponse() {
        response = body(gate + ":" + UUID.randomUUID(), "ACTIVE");
        rejected(() -> client.requireQueueOwner(gate, visitor, token), 503);
    }

    private void rejected(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(code));
    }
}
