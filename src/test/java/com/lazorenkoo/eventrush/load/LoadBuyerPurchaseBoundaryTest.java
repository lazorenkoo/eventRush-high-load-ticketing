package com.lazorenkoo.eventrush.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoadBuyerPurchaseBoundaryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LoadClientLeases leases = mock(LoadClientLeases.class);
    private final LoadBuyerHttp.Visit visit = mock(LoadBuyerHttp.Visit.class);
    private final LoadClientLeases.Client client = new LoadClientLeases.Client(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "First", "Last", UUID.randomUUID());
    private final UUID participant = UUID.randomUUID();
    private final UUID reservation = UUID.randomUUID();
    private final UUID payment = UUID.randomUUID();
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger reads = new AtomicInteger();
    private final LoadBuyerPurchase service = new LoadBuyerPurchase(mapper, jdbc, leases);
    private String scenario;

    @BeforeEach
    void setup() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(client.runId()))).thenReturn(true);
        when(leases.renew(client.id(), client.leaseToken())).thenReturn(true);
        when(visit.send(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String path = invocation.getArgument(1);
            if (path.contains("/reserve?")) {
                if (scenario.equals("bad-reservation")) return response(201, "[]");
                return response(201, mapper.writeValueAsString(Map.of("id", reservation,
                        "seatId", client.seatId(), "status", "ACTIVE")));
            }
            if (path.endsWith("/pay")) {
                int attempt = attempts.incrementAndGet();
                switch (scenario) {
                    case "interrupt" -> Thread.currentThread().interrupt();
                    case "stop" -> when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(client.runId())))
                            .thenReturn(false);
                    case "lease" -> when(leases.renew(client.id(), client.leaseToken())).thenReturn(false);
                    case "bad-payment" -> { return response(202, "{"); }
                    default -> { }
                }
                UUID id = scenario.equals("changed-payment") && attempt > 1 ? UUID.randomUUID() : payment;
                return response(202, mapper.writeValueAsString(Map.of("id", id, "reservationId", reservation,
                        "status", "PENDING")));
            }
            if (path.equals("/api/v1/reservations/" + reservation)) {
                reads.incrementAndGet();
                if (scenario.equals("bad-confirmation")) return response(200, "{}");
                return response(200, mapper.writeValueAsString(Map.of("id", reservation, "seatId", client.seatId(),
                        "status", scenario.equals("expired") ? "EXPIRED" : "ACTIVE")));
            }
            throw new AssertionError("Unexpected request");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"interrupt", "stop", "lease"})
    void stopsAfterPaymentWithoutReadingOrRepeatingHttp(String mode) {
        scenario = mode;
        try {
            assertThatThrownBy(() -> service.purchase(client, participant, visit)).isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(reads.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-reservation", "bad-payment", "bad-confirmation", "changed-payment", "expired"})
    void cannotSucceedWithMalformedOrChangingPaymentProof(String mode) {
        scenario = mode;
        assertThatThrownBy(() -> service.purchase(client, participant, visit)).isInstanceOf(IOException.class);
        if (mode.equals("bad-reservation")) assertThat(attempts.get()).isZero();
        if (mode.equals("changed-payment")) {
            assertThat(attempts.get()).isEqualTo(2);
            assertThat(reads.get()).isEqualTo(1);
        }
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, String json) {
        return mock(HttpResponse.class, invocation -> switch (invocation.getMethod().getName()) {
            case "statusCode" -> status;
            case "body" -> json.getBytes(StandardCharsets.UTF_8);
            default -> throw new AssertionError(invocation.getMethod().getName());
        });
    }
}
