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
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class RelayForgeAttemptTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CopyOnWriteArrayList<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private RelayForgeClient client;
    private volatile String body = "[]";

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/deliveries", exchange -> {
            requests.add(exchange.getRequestMethod() + "|" + exchange.getRequestURI() + "|"
                    + exchange.getRequestHeaders().getFirst("X-API-Key"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        client = new RelayForgeClient(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "fixture-key", Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @AfterEach
    void close() {
        server.stop(0);
    }

    private String attempt() {
        return """
                {"id":9,"deliveryId":7,"attemptNumber":2,"httpStatus":503,
                 "completedAt":"2026-10-23T02:00:00Z","status":"FAILED","error":"private destination"}
                """;
    }

    @Test
    void authenticatesExactPathAndExcludesPrivateErrorFromImmutableHistory() throws Exception {
        body = "[" + attempt() + "]";
        var rows = client.attempts(7);
        assertThat(requests).containsExactly("GET|/api/v1/deliveries/7/attempts|fixture-key");
        assertThat(rows).containsExactly(new RelayForgeClient.AttemptView(9, 7, 2, 503,
                Instant.parse("2026-10-23T02:00:00Z"), "FAILED"));
        assertThatThrownBy(rows::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(mapper.valueToTree(rows.getFirst()).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "deliveryId", "attemptNumber", "httpStatus", "completedAt", "status");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id=0", "id=1.5", "deliveryId=8", "attemptNumber=0", "attemptNumber=1.5",
            "attemptNumber=2147483648", "httpStatus=600", "httpStatus=\"503\"", "completedAt=\"invalid\"",
            "status=\"UNKNOWN\"", "status=null"})
    void rejectsInvalidOrCrossDeliveryAttempts(String mutation) throws Exception {
        var row = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(attempt());
        String[] parts = mutation.split("=", 2);
        row.set(parts[0], mapper.readTree(parts[1]));
        body = "[" + row + "]";
        assertThatThrownBy(() -> client.attempts(7)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid RelayForge delivery response");
    }

    @Test
    void rejectsDuplicateAttemptsAndInvalidRequestedIdentity() throws Exception {
        assertThat(client.attempts(7)).isEmpty();
        assertThatThrownBy(() -> client.attempts(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasSize(1);
        body = "[" + attempt() + "," + attempt() + "]";
        assertThatThrownBy(() -> client.attempts(7)).isInstanceOf(IllegalStateException.class);
        var second = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(attempt());
        second.put("id", 10);
        body = "[" + attempt() + "," + second + "]";
        assertThatThrownBy(() -> client.attempts(7)).isInstanceOf(IllegalStateException.class);
    }
}
