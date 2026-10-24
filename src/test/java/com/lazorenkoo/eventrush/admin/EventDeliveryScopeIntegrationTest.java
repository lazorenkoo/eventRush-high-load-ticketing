package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
import com.lazorenkoo.eventrush.relayforge.RelayForgeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///event_delivery_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
@Transactional
class EventDeliveryScopeIntegrationTest {
    private static final String KEY = "scope-test-admin-key-1234567890123456";
    @Autowired private JdbcTemplate jdbc;
    private final RelayForgeClient relay = mock(RelayForgeClient.class);
    private final UUID concert = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new EventDeliveryController(jdbc, relay))
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(new AdminAuthenticationFilter(KEY)).build();
    }

    private UUID event(String payload, boolean published) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into outgoing_events(id,event_type,payload,published_at)
                values (?,'ticket.purchased',?,case when ? then now() else null end)
                """, id, payload, published);
        return id;
    }

    private String path(UUID owner, UUID event) {
        return "/api/v1/admin/concerts/" + owner + "/events/" + event + "/deliveries";
    }

    @Test
    void onlyPublishedEventWithMatchingConcertCanReachRelayForge() throws Exception {
        String payload = "{ \"concertId\": \"" + concert + "\", \"buyer\": \"private\" }";
        UUID published = event(payload, true);
        UUID pending = event(payload, false);
        UUID other = event("{\"concertId\":\"" + UUID.randomUUID() + "\"}", true);
        UUID missingConcert = event("{}", true);
        for (UUID id : List.of(pending, other, missingConcert, UUID.randomUUID())) {
            mvc.perform(get(path(concert, id)).header("X-Admin-Key", KEY)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PUBLISHED_EVENT_NOT_FOUND"));
        }
        mvc.perform(get(path(UUID.randomUUID(), published)).header("X-Admin-Key", KEY))
                .andExpect(status().isNotFound());
        verifyNoInteractions(relay);
        when(relay.publishedId(published, "ticket.purchased", payload)).thenReturn(42L);
        when(relay.deliveries(42)).thenReturn(List.of());
        mvc.perform(get(path(concert, published)).header("X-Admin-Key", KEY)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.eventId").value(published.toString()))
                .andExpect(jsonPath("$.deliveries").isEmpty());
        verify(relay).publishedId(published, "ticket.purchased", payload);
        verify(relay).deliveries(42);
        verifyNoMoreInteractions(relay);
    }
}
