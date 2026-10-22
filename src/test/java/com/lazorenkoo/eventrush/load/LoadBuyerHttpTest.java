package com.lazorenkoo.eventrush.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadBuyerHttpTest {
    private HttpServer server;
    private LoadBuyerHttp transport;
    private final List<Seen> requests = new CopyOnWriteArrayList<>();
    private volatile byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            requests.add(new Seen(exchange.getRequestHeaders().getFirst("Cookie"),
                    exchange.getRequestHeaders().getFirst("Origin"), exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (exchange.getRequestURI().getPath().endsWith("redirect")) {
                exchange.getResponseHeaders().add("Location", "/api/v1/target");
                exchange.sendResponseHeaders(302, -1);
            } else {
                exchange.getResponseHeaders().add("Set-Cookie", "app_session=private-session; Path=/api/v1/secure");
                exchange.sendResponseHeaders(200, body.length);
                try {
                    exchange.getResponseBody().write(body);
                } catch (IOException ignored) {
                    // The client deliberately cancels oversized responses.
                }
            }
            exchange.close();
        });
        server.start();
        transport = new LoadBuyerHttp(new ObjectMapper(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "http://localhost:8082");
    }

    @AfterEach
    void close() {
        server.stop(0);
        transport.close();
    }

    @Test
    void isolatesVisitorAndSessionCookiesAndRespectsCookiePath() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (var a = transport.visit(first); var b = transport.visit(second)) {
            a.send("POST", "/api/v1/open", Map.of("firstName", "Alice"));
            a.send("GET", "/api/v1/secure/check", null);
            b.send("GET", "/api/v1/secure/check", null);
            a.send("GET", "/api/v1/open", null);
        }
        assertThat(requests.get(0).cookie()).contains(first.toString()).doesNotContain("private-session");
        assertThat(requests.get(1).cookie()).contains(first.toString(), "private-session");
        assertThat(requests.get(2).cookie()).contains(second.toString()).doesNotContain(first.toString(), "private-session");
        assertThat(requests.get(3).cookie()).doesNotContain("private-session");
        assertThat(requests.get(0).origin()).isEqualTo("http://localhost:8082");
        assertThat(requests.get(0).method()).isEqualTo("POST");
        assertThat(requests.get(0).type()).isEqualTo("application/json");
        assertThat(new ObjectMapper().readTree(requests.get(0).body()).path("firstName").asText()).isEqualTo("Alice");
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        try (var visit = transport.visit(UUID.randomUUID())) {
            assertThat(visit.send("GET", "/api/v1/redirect", null).statusCode()).isEqualTo(302);
        }
        assertThat(requests).hasSize(1);
    }

    @Test
    void acceptsSixtyFourKiBAndCancelsOneByteMore() throws Exception {
        try (var visit = transport.visit(UUID.randomUUID())) {
            body = new byte[65536];
            assertThat(visit.send("GET", "/api/v1/check", null).body()).hasSize(65536);
            body = new byte[65537];
            assertThatThrownBy(() -> visit.send("GET", "/api/v1/check", null)).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/../admin", "/api/v1/%2e%2e/admin", "https://example.com/api/v1/check",
            "//example.com/api/v1/check", "/webhooks/test", "/api/v1/check#fragment"})
    void rejectsPathsBeforeSendingAnything(String path) {
        try (var visit = transport.visit(UUID.randomUUID())) {
            assertThatThrownBy(() -> visit.send("GET", path, null)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(requests).isEmpty();
    }

    private record Seen(String cookie, String origin, String method, String type, String body) {
    }
}
