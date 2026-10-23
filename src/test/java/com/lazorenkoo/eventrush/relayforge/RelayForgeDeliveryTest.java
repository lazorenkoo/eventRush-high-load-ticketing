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

class RelayForgeDeliveryTest {
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

    private String delivery() {
        return """
                {"id":7,"eventId":42,"endpointId":3,"status":"FAILED","httpStatus":503,
                 "attemptCount":2,"nextAttemptAt":"2026-10-23T02:00:00Z","completedAt":null,
                 "lastError":"private error","payload":"private buyer","url":"private destination"}
                """;
    }

    @Test
    void scopesAuthenticatedGetAndReturnsOnlyImmutableDeliverySummary() throws Exception {
        body = "[" + delivery() + "]";
        var rows = client.deliveries(42);
        assertThat(requests).containsExactly("GET|/api/v1/deliveries?eventId=42|fixture-key");
        assertThat(rows).containsExactly(new RelayForgeClient.DeliveryView(7, 42, 3, "FAILED", 503, 2,
                Instant.parse("2026-10-23T02:00:00Z"), null));
        assertThatThrownBy(rows::clear).isInstanceOf(UnsupportedOperationException.class);
        var fields = mapper.valueToTree(rows.getFirst()).fieldNames();
        assertThat(fields).toIterable().containsExactlyInAnyOrder("id", "eventId", "endpointId", "status",
                "httpStatus", "attemptCount", "nextAttemptAt", "completedAt");
    }

    @Test
    void emptyListAndInvalidRequestedIdentityDoNotCreateDeliveries() {
        assertThat(client.deliveries(42)).isEmpty();
        assertThatThrownBy(() -> client.deliveries(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.deliveries(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id=0", "id=1.5", "id=9223372036854775808", "eventId=43", "endpointId=-1",
            "status=\"UNKNOWN\"", "status=null", "attemptCount=-1", "attemptCount=2147483648",
            "httpStatus=99", "httpStatus=600", "httpStatus=\"503\"", "completedAt=\"yesterday\"",
            "nextAttemptAt=42"})
    void rejectsInvalidOrCrossEventRowsWithoutLeakingPrivateFields(String mutation) throws Exception {
        var row = mapper.readTree(delivery());
        String[] parts = mutation.split("=", 2);
        ((com.fasterxml.jackson.databind.node.ObjectNode) row).set(parts[0], mapper.readTree(parts[1]));
        body = "[" + row + "]";
        assertThatThrownBy(() -> client.deliveries(42)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid RelayForge delivery response");
    }

    @Test
    void rejectsDuplicateIdentitiesAndOversizedCollections() {
        body = "[" + delivery() + "," + delivery() + "]";
        assertThatThrownBy(() -> client.deliveries(42)).isInstanceOf(IllegalStateException.class);
        body = "[" + "{},".repeat(100) + "{}]";
        assertThatThrownBy(() -> client.deliveries(42)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid RelayForge delivery response");
    }
}
