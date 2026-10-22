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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoadBuyerPurchaseTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LoadClientLeases leases = mock(LoadClientLeases.class);
    private final LoadClientLeases.Client client = new LoadClientLeases.Client(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "First", "Last", UUID.randomUUID());
    private final UUID participant = UUID.randomUUID();
    private final UUID reservation = UUID.randomUUID();
    private final UUID payment = UUID.randomUUID();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger payments = new AtomicInteger();
    private HttpServer server;
    private LoadBuyerHttp transport;
    private volatile String scenario = "success";
    private volatile String paymentCookie;
    private volatile String paymentMode;

    @BeforeEach
    void setup() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(client.runId()))).thenReturn(true);
        when(leases.renew(client.id(), client.leaseToken())).thenReturn(true);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            int status = 200;
            Object body = Map.of();
            if (path.endsWith("/reserve")) {
                status = 201;
                body = Map.of("id", reservation, "seatId", client.seatId(), "status", "ACTIVE");
                exchange.getResponseHeaders().add("Set-Cookie", "app_session=fixture; Path=/");
                if (scenario.equals("conflict") || scenario.equals("other-conflict")) {
                    status = 409;
                    body = Map.of("code", scenario.equals("conflict") ? "SEAT_ALREADY_RESERVED" : "SALE_NOT_OPEN");
                }
                if (scenario.equals("wrong-seat")) {
                    body = Map.of("id", reservation, "seatId", UUID.randomUUID(), "status", "ACTIVE");
                }
            } else if (path.equals("/api/v1/reservations/" + reservation + "/pay")) {
                int attempt = payments.incrementAndGet();
                paymentCookie = exchange.getRequestHeaders().getFirst("Cookie");
                paymentMode = mapper.readTree(exchange.getRequestBody()).path("mode").asText();
                UUID owner = scenario.equals("foreign-payment") ? UUID.randomUUID() : reservation;
                String state = scenario.equals("failed") ? "FAILED" : attempt == 1 ? "PENDING" : "SUCCESS";
                body = Map.of("id", payment, "reservationId", owner, "status", state);
                status = state.equals("PENDING") ? 202 : 200;
            } else if (path.equals("/api/v1/reservations/" + reservation)) {
                UUID id = scenario.equals("foreign-confirmation") ? UUID.randomUUID() : reservation;
                body = Map.of("id", id, "seatId", client.seatId(),
                        "status", payments.get() == 1 ? "ACTIVE" : "CONFIRMED");
            } else status = 404;
            byte[] bytes = mapper.writeValueAsBytes(body);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
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
    void pollsSamePaymentUntilSuccessAndConfirmedReservation() throws Exception {
        try (var visit = transport.visit(client.id())) {
            var service = new LoadBuyerPurchase(mapper, jdbc, leases);
            assertThat(service.purchase(client, participant, visit)).isEqualTo(LoadClientResults.Outcome.SUCCEEDED);
        }
        assertThat(payments.get()).isEqualTo(2);
        assertThat(paymentMode).isEqualTo("SUCCESS");
        assertThat(paymentCookie).contains("cg_user_id=" + client.id(), "app_session=fixture");
        assertThat(requests.getFirst()).isEqualTo("POST /api/v1/seats/" + client.seatId()
                + "/reserve?participantId=" + participant);
        assertThat(requests).hasSize(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"conflict", "other-conflict", "wrong-seat", "failed", "foreign-payment", "foreign-confirmation"})
    void rejectsFailuresAndForeignProofWithoutReportingPurchase(String mode) throws Exception {
        scenario = mode;
        try (var visit = transport.visit(client.id())) {
            var service = new LoadBuyerPurchase(mapper, jdbc, leases);
            if (mode.equals("conflict")) {
                assertThat(service.purchase(client, participant, visit)).isEqualTo(LoadClientResults.Outcome.SOLD_OUT);
            } else assertThatThrownBy(() -> service.purchase(client, participant, visit)).isInstanceOf(IOException.class);
        }
        if (mode.contains("conflict") || mode.equals("wrong-seat")) assertThat(requests).hasSize(1);
    }
}
