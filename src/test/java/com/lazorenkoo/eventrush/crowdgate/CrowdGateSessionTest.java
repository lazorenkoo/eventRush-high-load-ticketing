package com.lazorenkoo.eventrush.crowdgate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrowdGateSessionTest {

    private final UUID gate = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final String credential = gate + ":" + "a".repeat(43);
    private final CrowdGateClient.Admission admission =
            new CrowdGateClient.Admission(gate, owner, UUID.randomUUID(), credential);
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private CrowdGateClient client;
    private volatile String method;
    private volatile String path;
    private volatile String cookies;
    private String response;
    private int status = 200;
    private final Instant expiresAt = Instant.now().plusSeconds(60);

    @BeforeEach
    void startServer() throws Exception {
        response = "{\"gateId\":\"" + gate + "\",\"expiresAtMillis\":" + expiresAt.toEpochMilli() + "}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/gates/" + gate + "/session", exchange -> {
            requests.incrementAndGet();
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            cookies = exchange.getRequestHeaders().getFirst("Cookie");
            byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new CrowdGateClient(new ObjectMapper(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checksOrRenewsBoundSessionAndReturnsExpiry(boolean heartbeat) {
        Instant result = heartbeat ? client.heartbeat(admission) : client.validateSession(admission);
        assertThat(result.toEpochMilli()).isEqualTo(expiresAt.toEpochMilli());
        assertThat(method).isEqualTo(heartbeat ? "POST" : "GET");
        assertThat(path).isEqualTo("/api/v1/gates/" + gate + "/session" + (heartbeat ? "/heartbeat" : ""));
        assertThat(cookies).isEqualTo("cg_user_id=" + owner + "; cg_session=" + credential);
    }

    @ParameterizedTest
    @CsvSource({"401,401", "410,401", "429,503", "503,503", "302,503"})
    void rejectsInvalidSessionsAndUnavailableUpstream(int upstream, int expected) {
        status = upstream;
        rejected(expected);
    }

    @Test
    void rejectsExpiredWrongGateAndMalformedResponses() {
        response = "{\"gateId\":\"" + gate + "\",\"expiresAtMillis\":1}";
        rejected(401);
        response = "{\"gateId\":\"" + UUID.randomUUID() + "\",\"expiresAtMillis\":9999999999999}";
        rejected(503);
        response = "{}";
        rejected(503);
    }

    @Test
    void rejectsMalformedCredentialBeforeSending() {
        var invalid = new CrowdGateClient.Admission(gate, owner, UUID.randomUUID(), credential + "\r\nInjected");
        assertThatThrownBy(() -> client.validateSession(invalid))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        assertThat(requests).hasValue(0);
    }

    private void rejected(int expected) {
        assertThatThrownBy(() -> client.validateSession(admission))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(expected));
    }
}
