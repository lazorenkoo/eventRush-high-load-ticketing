package com.lazorenkoo.eventrush.crowdgate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrowdGateClientTest {

    private final UUID gate = UUID.randomUUID();
    private final UUID visitor = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private CrowdGateClient client;
    private String cookie;
    private String response;
    private int status = 200;
    private boolean holdResponse;
    private volatile String receivedCookie;
    private volatile String receivedToken;

    @BeforeEach
    void startServer() throws Exception {
        cookie = "cg_session=" + gate + ":secret; Max-Age=60; HttpOnly; SameSite=Lax";
        response = "{\"queueToken\":\"" + gate + ":" + session + "\",\"status\":\"ACTIVE\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/tokens/redeem", exchange -> {
            requests.incrementAndGet();
            receivedCookie = exchange.getRequestHeaders().getFirst("Cookie");
            receivedToken = mapper.readTree(exchange.getRequestBody()).path("admissionToken").asText();
            try {
                if (holdResponse) {
                    release.await(3, TimeUnit.SECONDS);
                }
                if (cookie != null) {
                    exchange.getResponseHeaders().add("Set-Cookie", cookie);
                }
                byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        client = new CrowdGateClient(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1), Duration.ofMillis(500));
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void forwardsOwnerAndTokenAndReturnsActivatedSessionWithoutLoggingCredential() {
        var admission = client.redeem(gate, visitor, "signed-token");
        assertThat(receivedCookie).isEqualTo("cg_user_id=" + visitor);
        assertThat(receivedToken).isEqualTo("signed-token");
        assertThat(admission.gateId()).isEqualTo(gate);
        assertThat(admission.visitorId()).isEqualTo(visitor);
        assertThat(admission.queueSessionId()).isEqualTo(session);
        assertThat(admission.sessionCredential()).isEqualTo(gate + ":secret");
        assertThat(admission.toString()).doesNotContain("secret");
    }

    @ParameterizedTest
    @CsvSource({"401,403", "409,403", "410,403", "429,503", "503,503", "302,503"})
    void rejectsUpstreamFailure(int upstream, int expected) {
        status = upstream;
        rejected(expected, gate);
        assertThat(requests).hasValue(1);
    }

    @Test
    void rejectsWrongGateInactiveResponseAndMissingOrExpiredSession() {
        rejected(403, UUID.randomUUID());
        response = response.replace("ACTIVE", "WAITING");
        rejected(403, gate);
        response = response.replace("WAITING", "ACTIVE");
        cookie = null;
        rejected(503, gate);
        cookie = "cg_session=expired; Max-Age=0";
        rejected(503, gate);
    }

    @Test
    void timeoutFailsClosed() {
        holdResponse = true;
        rejected(503, gate);
    }

    private void rejected(int expected, UUID expectedGate) {
        assertThatThrownBy(() -> client.redeem(expectedGate, visitor, "signed-token"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(expected));
    }
}
