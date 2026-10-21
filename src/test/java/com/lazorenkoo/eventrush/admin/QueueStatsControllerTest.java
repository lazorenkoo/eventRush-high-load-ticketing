package com.lazorenkoo.eventrush.admin;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueStatsControllerTest {
    private HttpServer server;
    private QueueStatsController controller;
    private String body;
    private int status;
    private final AtomicReference<String> credential = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        body = metrics("12", "2.5");
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/prometheus", exchange -> {
            credential.set(exchange.getRequestHeaders().getFirst("X-Admin-Key"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
            exchange.close();
        });
        server.start();
        controller = new QueueStatsController(origin(), "test-key");
    }

    @AfterEach
    void stop() { server.stop(0); }

    private URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

    private String metrics(String waiting, String age) {
        return "# HELP unrelated ignored\n"
                + "crowdgate_queue_size " + waiting + "\n"
                + "crowdgate_reserved_slots 3\n"
                + "crowdgate_active_sessions 4\n"
                + "crowdgate_metrics_snapshot_age_seconds " + age + "\n";
    }

    @Test
    void readsGlobalSnapshotWithPrivateCredentialAndNoCache() {
        var response = controller.read();
        var stats = response.getBody();
        assertThat(stats.scope()).isEqualTo("ALL_GATES");
        assertThat(stats.waiting()).isEqualTo(12);
        assertThat(stats.admitted()).isEqualTo(3);
        assertThat(stats.active()).isEqualTo(4);
        assertThat(stats.snapshotAgeSeconds()).isEqualTo(2.5);
        assertThat(stats.fetchedAt()).isNotNull();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(credential.get()).isEqualTo("test-key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-1", "1.5", "9223372036854775808", "invalid"})
    void rejectsInvalidCounts(String value) {
        body = metrics(value, "0");
        unavailable();
    }

    @ParameterizedTest
    @ValueSource(strings = {"30.01", "NaN", "-1", "Infinity"})
    void rejectsStaleOrInvalidAge(String age) {
        body = metrics("0", age);
        unavailable();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500, 302})
    void rejectsUpstreamErrorsAndRedirects(int code) {
        status = code;
        unavailable();
    }

    @Test
    void rejectsMissingMetricsAndOversizedBodies() {
        body = "unrelated_metric 0\n";
        unavailable();
        body = "x".repeat(131073);
        unavailable();
        assertThatThrownBy(() -> new QueueStatsController(origin(), "").read())
                .isInstanceOf(ResponseStatusException.class);
    }

    private void unavailable() {
        assertThatThrownBy(controller::read).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
    }
}
