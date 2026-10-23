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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class RelayForgeIdentityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CopyOnWriteArrayList<String> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch headers = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private RelayForgeClient client;
    private volatile String body = "{\"id\":42}";
    private volatile boolean stall;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/events", exchange -> {
            requests.add(exchange.getRequestHeaders().getFirst("Idempotency-Key") + "|"
                    + exchange.getRequestHeaders().getFirst("X-API-Key") + "|"
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, bytes.length == 0 ? -1 : bytes.length);
                if (stall) {
                    exchange.getResponseBody().write(bytes, 0, 1);
                    exchange.getResponseBody().flush();
                    headers.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } else exchange.getResponseBody().write(bytes);
            } catch (Exception ignored) {
                // Timeout and interruption intentionally cancel the response stream.
            } finally {
                exchange.close();
            }
        });
        server.start();
        client = new RelayForgeClient(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "fixture-key", Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @AfterEach
    void close() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void repeatedLookupPreservesIdempotencyKeyAndExactPayload() throws Exception {
        UUID event = UUID.randomUUID();
        String payload = "{ \"name\": \"Проверка\", \"amount\": 25.00 }";
        assertThat(client.publishedId(event, "ticket.purchased", payload)).isEqualTo(42);
        assertThat(client.publishedId(event, "ticket.purchased", payload)).isEqualTo(42);
        assertThat(requests).hasSize(2).allMatch(requests.getFirst()::equals);
        assertThat(requests.getFirst()).startsWith(event + "|fixture-key|");
        var sent = mapper.readTree(requests.getFirst().split("\\|", 3)[2]);
        assertThat(sent.path("payload").asText()).isEqualTo(payload);
        assertThat(sent.path("type").asText()).isEqualTo("ticket.purchased");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "[]", "{", "{\"id\":0}", "{\"id\":-1}", "{\"id\":1.5}",
            "{\"id\":\"42\"}", "{\"id\":9223372036854775808}"})
    void rejectsMissingMalformedAndNonPositiveIntegerIdentities(String value) {
        body = value;
        assertThatThrownBy(() -> client.publishedId(UUID.randomUUID(), "ticket.purchased", "{}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialBodyTimeoutAndInterruptTerminateWorker(boolean interrupt) throws Exception {
        stall = true;
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicReference<Boolean>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                client.publishedId(UUID.randomUUID(), "ticket.purchased", "{}");
            } catch (Throwable error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertThat(headers.await(2, TimeUnit.SECONDS)).isTrue();
            if (interrupt) worker.interrupt();
            worker.join(3000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
            assertThat(interrupted.get()).isEqualTo(interrupt);
        } finally {
            release.countDown();
            worker.interrupt();
        }
    }
}
