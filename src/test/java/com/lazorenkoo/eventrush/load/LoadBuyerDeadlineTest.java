package com.lazorenkoo.eventrush.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LoadBuyerDeadlineTest {
    private HttpServer server;
    private LoadBuyerHttp transport;
    private final CountDownLatch headers = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<String> nextCookie = new AtomicReference<>();

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            try {
                boolean stalled = exchange.getRequestURI().getPath().endsWith("stall");
                if (stalled) exchange.getResponseHeaders().add("Set-Cookie", "app_session=late-secret; Path=/");
                else nextCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                if (stalled) {
                    headers.countDown();
                    release.await(10, TimeUnit.SECONDS);
                }
                exchange.getResponseBody().write('}');
            } catch (IOException ignored) {
                // Cancelling the buyer deliberately closes this response.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        transport = new LoadBuyerHttp(new ObjectMapper(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "http://localhost:8082");
    }

    @AfterEach
    void close() {
        release.countDown();
        server.stop(0);
        transport.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stalledBodyIsCancelledAndCannotInstallResponseCookies(boolean interrupt) throws Exception {
        UUID visitor = UUID.randomUUID();
        try (var visit = transport.visit(visitor)) {
            var failure = new AtomicReference<Throwable>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    visit.send("GET", "/api/v1/stall", null);
                } catch (Throwable exception) {
                    failure.set(exception);
                }
            });
            try {
                assertThat(headers.await(3, TimeUnit.SECONDS)).isTrue();
                if (interrupt) worker.interrupt();
                worker.join(7000);
                assertThat(worker.isAlive()).isFalse();
                assertThat(failure.get()).isInstanceOf(interrupt ? InterruptedException.class : HttpTimeoutException.class);
                release.countDown();
                assertThat(visit.send("GET", "/api/v1/check", null).statusCode()).isEqualTo(200);
                assertThat(nextCookie.get()).contains(visitor.toString()).doesNotContain("late-secret");
            } finally {
                worker.interrupt();
                release.countDown();
                worker.join(1000);
            }
        }
    }
}
