package com.lazorenkoo.eventrush.downstream;

import com.lazorenkoo.eventrush.admission.AdmissionSessionStore;
import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient.Admission;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
@Transactional
class OwnerResultsIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdmissionSessionStore sessions;
    @Autowired
    private OwnerResultsController controller;
    private MockMvc mvc;
    private Cookie cookie;
    private final UUID owner = UUID.randomUUID();
    private UUID latest;

    @BeforeEach
    void setup() {
        UUID gate = UUID.randomUUID();
        jdbc.update("insert into concerts(name, starts_at, sale_starts_at, crowdgate_gate_id) "
                + "values('Owner results',now()+interval '1 day',now(),?)", gate);
        String token = sessions.create(new Admission(gate, owner, UUID.randomUUID(), gate + ":" + "a".repeat(43)),
                Instant.now().plusSeconds(60));
        cookie = new Cookie("eventrush_session", token);
        record(owner);
        latest = record(owner);
        record(UUID.randomUUID());
        jdbc.update("insert into simulated_notifications(event_id,event_type,payload) "
                + "values(?,'concert.sold_out','{}')", UUID.randomUUID());
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tickets", "notifications"})
    void returnsOnlyOwnedResultsWithoutPrivateFields(String resource) throws Exception {
        mvc.perform(get("/api/v1/me/" + resource).cookie(cookie)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").doesNotExist()).andExpect(jsonPath("$[0].payload").doesNotExist());
        String field = resource.equals("tickets") ? "reservationId" : "eventId";
        mvc.perform(get("/api/v1/me/" + resource + "?limit=1").cookie(cookie))
                .andExpect(jsonPath("$[0]." + field).value(latest.toString()));
        mvc.perform(get("/api/v1/me/" + resource + "?limit=1&offset=1").cookie(cookie))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/me/" + resource + "?offset=2").cookie(cookie))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=101", "offset=-1", "offset=10001", "limit=invalid"})
    void rejectsInvalidPagination(String query) throws Exception {
        for (String resource : new String[]{"tickets", "notifications"}) {
            mvc.perform(get("/api/v1/me/" + resource + "?" + query).cookie(cookie))
                    .andExpect(status().isBadRequest());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "revoked", "expired"})
    void requiresAnActiveApplicationSession(String state) throws Exception {
        if (state.equals("revoked")) sessions.revoke(cookie.getValue());
        if (state.equals("expired")) {
            jdbc.update("update admission_sessions set created_at=now()-interval '2 seconds', "
                    + "expires_at=now()-interval '1 second'");
        }
        for (String resource : new String[]{"tickets", "notifications"}) {
            var request = get("/api/v1/me/" + resource);
            if (!state.equals("missing")) request.cookie(cookie);
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void gateExpiryDoesNotHidePreviouslyIssuedOwnerResults() throws Exception {
        jdbc.update("update admission_sessions set admission_expires_at=now()-interval '1 second'");
        for (String resource : new String[]{"tickets", "notifications"}) {
            mvc.perform(get("/api/v1/me/" + resource).cookie(cookie)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }
    }

    private UUID record(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ticket_records(event_id,reservation_id,concert_id,seat_id,user_id) "
                + "values(?,?,?,?,?)", id, id, id, id, user);
        jdbc.update("insert into simulated_notifications(event_id,event_type,user_id,payload) "
                + "values(?,'ticket.purchased',?,'{}')", id, user);
        return id;
    }
}
