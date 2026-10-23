package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
import com.lazorenkoo.eventrush.relayforge.RelayForgeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EventDeliveryControllerTest {
    private static final String KEY = "delivery-test-admin-key-1234567890123456";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RelayForgeClient relay = mock(RelayForgeClient.class);
    private final UUID concert = UUID.randomUUID();
    private final UUID event = UUID.randomUUID();
    private final String payload = "{ \"concertId\": \"" + concert + "\", \"name\": \"private\" }";
    private final String path = "/api/v1/admin/concerts/" + concert + "/events/" + event + "/deliveries";
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EventDeliveryController(jdbc, relay))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new AdminAuthenticationFilter(KEY)).build();

    private void published(boolean exists) {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(event), eq(concert.toString())))
                .thenAnswer(call -> {
                    String sql = call.getArgument(0);
                    assertThat(sql).contains("id=?", "published_at is not null", "payload::jsonb->>'concertId'=?");
                    if (!exists) return List.of();
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString("event_type")).thenReturn("ticket.purchased");
                    when(row.getString("payload")).thenReturn(payload);
                    RowMapper<?> mapper = call.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
    }

    @Test
    void returnsAttemptsOnlyForDeliveryBelongingToScopedEvent() throws Exception {
        published(true);
        when(relay.publishedId(event, "ticket.purchased", payload)).thenReturn(42L);
        when(relay.deliveries(42)).thenReturn(List.of(
                new RelayForgeClient.DeliveryView(7, 42, 3, "FAILED", 503, 2, null, null)));
        when(relay.attempts(7)).thenReturn(List.of(
                new RelayForgeClient.AttemptView(9, 7, 2, 503, null, "FAILED")));
        mvc.perform(get(path + "/7/attempts").header("X-Admin-Key", KEY)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].deliveryId").value(7))
                .andExpect(jsonPath("$[0].attemptNumber").value(2))
                .andExpect(jsonPath("$[0].error").doesNotExist());
        mvc.perform(get(path + "/8/attempts").header("X-Admin-Key", KEY)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_DELIVERY_NOT_FOUND"));
        verify(relay).attempts(7);
        verify(relay, never()).attempts(8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid"})
    void malformedDeliveryIdentityNeverCallsDatabaseOrRemote(String id) throws Exception {
        mvc.perform(get(path + "/" + id + "/attempts").header("X-Admin-Key", KEY))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(jdbc, relay);
    }

    @Test
    void unauthenticatedAttemptRequestNeverContactsDependencies() throws Exception {
        mvc.perform(get(path + "/7/attempts")).andExpect(status().isUnauthorized());
        verifyNoInteractions(jdbc, relay);
    }

    @Test
    void attemptHistoryFailureIsSanitizedAndCannotLeakRemoteDetails() throws Exception {
        published(true);
        when(relay.publishedId(event, "ticket.purchased", payload)).thenReturn(42L);
        when(relay.deliveries(42)).thenReturn(List.of(
                new RelayForgeClient.DeliveryView(7, 42, 3, "FAILED", 503, 2, null, null)));
        when(relay.attempts(7)).thenThrow(new IllegalStateException("private destination"));
        mvc.perform(get(path + "/7/attempts").header("X-Admin-Key", KEY))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RELAYFORGE_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }
    @Test
    void returnsScopedSummaryWithExactOriginalPublicationIdentity() throws Exception {
        published(true);
        when(relay.publishedId(event, "ticket.purchased", payload)).thenReturn(42L);
        when(relay.deliveries(42)).thenReturn(List.of(
                new RelayForgeClient.DeliveryView(7, 42, 3, "FAILED", 503, 2, null, null)));
        mvc.perform(get(path).header("X-Admin-Key", KEY)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.eventId").value(event.toString()))
                .andExpect(jsonPath("$.relayEventId").value(42))
                .andExpect(jsonPath("$.deliveries[0].status").value("FAILED"))
                .andExpect(jsonPath("$.payload").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        verify(relay).publishedId(event, "ticket.purchased", payload);
        verify(relay).deliveries(42);
        verifyNoMoreInteractions(relay);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong", "duplicate"})
    void rejectsInvalidAdminCredentialsBeforeDatabaseOrRemoteCalls(String mode) throws Exception {
        var request = get(path);
        if (!mode.equals("missing")) request.header("X-Admin-Key", mode.equals("wrong") ? "wrong" : KEY);
        if (mode.equals("duplicate")) request.header("X-Admin-Key", KEY);
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(jdbc, relay);
    }

    @Test
    void missingUnpublishedOrOtherConcertEventDoesNotContactRelayForge() throws Exception {
        published(false);
        mvc.perform(get(path).header("X-Admin-Key", KEY)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUBLISHED_EVENT_NOT_FOUND"));
        verifyNoInteractions(relay);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sanitizesBothRemoteFailureStages(boolean listing) throws Exception {
        published(true);
        if (listing) {
            when(relay.publishedId(event, "ticket.purchased", payload)).thenReturn(42L);
            when(relay.deliveries(42)).thenThrow(new IllegalStateException("private error"));
        } else when(relay.publishedId(event, "ticket.purchased", payload))
                .thenThrow(new IllegalStateException("private error"));
        mvc.perform(get(path).header("X-Admin-Key", KEY)).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RELAYFORGE_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }
}
