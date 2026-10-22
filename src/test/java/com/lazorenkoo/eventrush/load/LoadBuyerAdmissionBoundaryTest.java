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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class LoadBuyerAdmissionBoundaryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LoadClientLeases leases = mock(LoadClientLeases.class);
    private final LoadParticipantRegistration registration = mock(LoadParticipantRegistration.class);
    private final LoadBuyerHttp.Visit visit = mock(LoadBuyerHttp.Visit.class);
    private final LoadClientLeases.Client client = new LoadClientLeases.Client(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "First", "Last", UUID.randomUUID());
    private final UUID gate = UUID.randomUUID();
    private final String token = gate + ":" + UUID.randomUUID();
    private final String concertPath = "/api/v1/concerts/" + client.concertId();
    private final String joinPath = "/api/v1/gates/" + gate + "/join";
    private final LoadBuyerAdmission service = new LoadBuyerAdmission(mapper, jdbc, leases, registration);

    @BeforeEach
    void setup() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(client.runId()))).thenReturn(true);
        when(leases.renew(client.id(), client.leaseToken())).thenReturn(true);
        when(visit.send("GET", concertPath, null)).thenReturn(response(mapper.writeValueAsString(
                Map.of("id", client.concertId(), "crowdgateGateId", gate))));
        when(visit.send("POST", joinPath, null)).thenReturn(response(mapper.writeValueAsString(
                Map.of("queueToken", token, "status", "WAITING"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"interrupt", "stop", "lease"})
    void stopsDuringQueueWaitBeforeAnotherHttpRequest(String mode) throws Exception {
        when(registration.register(client.id(), client.leaseToken(), token)).thenAnswer(invocation -> {
            switch (mode) {
                case "interrupt" -> Thread.currentThread().interrupt();
                case "stop" -> when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(client.runId())))
                        .thenReturn(false);
                case "lease" -> when(leases.renew(client.id(), client.leaseToken())).thenReturn(false);
                default -> throw new AssertionError(mode);
            }
            return UUID.randomUUID();
        });
        try {
            assertThatThrownBy(() -> service.admit(client, visit)).isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }
        verify(visit).send("GET", concertPath, null);
        verify(visit).send("POST", joinPath, null);
        verifyNoMoreInteractions(visit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "array", "malformed", "wrong-concert", "noncanonical-gate",
            "short-session", "changed-token", "numeric-state"})
    void rejectsMalformedIdentityAndQueueResponses(String mode) throws Exception {
        String json = switch (mode) {
            case "empty" -> "";
            case "array" -> "[]";
            case "malformed" -> "{";
            case "wrong-concert" -> mapper.writeValueAsString(
                    Map.of("id", UUID.randomUUID(), "crowdgateGateId", gate));
            case "noncanonical-gate" -> mapper.writeValueAsString(
                    Map.of("id", client.concertId(), "crowdgateGateId", "1-1-1-1-1"));
            case "short-session" -> mapper.writeValueAsString(
                    Map.of("queueToken", gate + ":1-1-1-1-1", "status", "WAITING"));
            case "changed-token" -> mapper.writeValueAsString(
                    Map.of("queueToken", gate + ":" + UUID.randomUUID(), "status", "WAITING"));
            case "numeric-state" -> mapper.writeValueAsString(Map.of("queueToken", token, "status", 1));
            default -> throw new AssertionError(mode);
        };
        if (mode.equals("changed-token")) {
            when(registration.register(client.id(), client.leaseToken(), token)).thenReturn(UUID.randomUUID());
            when(visit.send("GET", "/api/v1/queue/" + token, null)).thenReturn(response(json));
        } else if (mode.equals("short-session") || mode.equals("numeric-state")) {
            when(visit.send("POST", joinPath, null)).thenReturn(response(json));
        } else when(visit.send("GET", concertPath, null)).thenReturn(response(json));
        assertThatThrownBy(() -> service.admit(client, visit)).isInstanceOf(IOException.class);
        verify(visit, never()).send(eq("POST"), contains("admission-token"), any());
        verify(visit, never()).send(eq("POST"), eq("/api/v1/admission/exchange"), any());
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(String json) {
        return mock(HttpResponse.class, invocation -> switch (invocation.getMethod().getName()) {
            case "statusCode" -> 200;
            case "body" -> json.getBytes(StandardCharsets.UTF_8);
            default -> throw new AssertionError(invocation.getMethod().getName());
        });
    }
}
