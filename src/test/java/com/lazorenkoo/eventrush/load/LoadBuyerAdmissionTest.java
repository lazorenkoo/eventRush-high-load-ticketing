package com.lazorenkoo.eventrush.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoadBuyerAdmissionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LoadClientLeases leases = mock(LoadClientLeases.class);
    private final LoadParticipantRegistration registration = mock(LoadParticipantRegistration.class);
    private final LoadClientLeases.Client client = new LoadClientLeases.Client(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "First", "Last", UUID.randomUUID());
    private final UUID gate = UUID.randomUUID();
    private final UUID participant = UUID.randomUUID();
    private final String token = gate + ":" + UUID.randomUUID();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private LoadBuyerHttp transport;
    private volatile String scenario = "success";
    private volatile String cookie;
    private volatile String exchangeBody;

    @BeforeEach
    void setup() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(client.runId()))).thenReturn(true);
        when(leases.renew(client.id(), client.leaseToken())).thenReturn(true);
        when(registration.register(client.id(), client.leaseToken(), token)).thenReturn(participant);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            Object value = Map.of();
            int status = 200;
            if (path.equals("/api/v1/concerts/" + client.concertId())) {
                value = Map.of("id", client.concertId(), "crowdgateGateId", gate);
            } else if (path.endsWith("/join")) {
                String queue = scenario.equals("foreign") ? UUID.randomUUID() + ":" + UUID.randomUUID() : token;
                value = Map.of("queueToken", queue, "status", scenario.equals("active") ? "ACTIVE" : "WAITING");
            } else if (path.equals("/api/v1/queue/" + token)) {
                value = Map.of("queueToken", token, "status", "ADMITTED");
            } else if (path.endsWith("/admission-token")) {
                value = Map.of("admissionToken", "fixture-token");
            } else if (path.endsWith("/exchange")) {
                exchangeBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                status = scenario.equals("rejected") ? 401 : 204;
                exchange.getResponseHeaders().add("Set-Cookie", "app_session=fixture; Path=/");
            } else if (path.endsWith("/seats")) {
                cookie = exchange.getRequestHeaders().getFirst("Cookie");
                value = List.of();
            } else status = 404;
            byte[] bytes = mapper.writeValueAsBytes(value);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        transport = new LoadBuyerHttp(mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "http://localhost:8082");
    }

    @AfterEach
    void close() {
        server.stop(0);
        transport.close();
    }

    @Test
    void waitsRegistersExchangesAndProvesSessionWithIndependentVisitorCookies() throws Exception {
        try (var visit = transport.visit(client.id())) {
            var service = new LoadBuyerAdmission(mapper, jdbc, leases, registration);
            assertThat(service.admit(client, visit)).isEqualTo(participant);
        }
        verify(registration, times(2)).register(client.id(), client.leaseToken(), token);
        assertThat(paths).hasSize(6);
        assertThat(cookie).contains("cg_user_id=" + client.id(), "app_session=fixture");
        assertThat(mapper.readTree(exchangeBody).path("concertId").asText()).isEqualTo(client.concertId().toString());
        assertThat(mapper.readTree(exchangeBody).path("admissionToken").asText()).isEqualTo("fixture-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "active", "rejected", "stopped", "expired"})
    void rejectsUnsafeAdmissionAndStoppedOrExpiredWorkers(String mode) throws Exception {
        scenario = mode;
        if (mode.equals("stopped")) {
            when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(client.runId()))).thenReturn(false);
        }
        if (mode.equals("expired")) {
            when(leases.renew(client.id(), client.leaseToken())).thenReturn(false);
        }
        try (var visit = transport.visit(client.id())) {
            assertThatThrownBy(() -> new LoadBuyerAdmission(mapper, jdbc, leases, registration).admit(client, visit))
                    .isInstanceOf(mode.equals("stopped") || mode.equals("expired")
                            ? InterruptedException.class : IOException.class);
        }
        assertThat(cookie).isNull();
        if (mode.equals("stopped") || mode.equals("expired")) assertThat(paths).isEmpty();
        if (mode.equals("foreign") || mode.equals("active")) verifyNoInteractions(registration);
    }
}
