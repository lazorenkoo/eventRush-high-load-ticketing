package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///concert_events_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
@Transactional
class ConcertEventListIntegrationTest {
    private static final String KEY = "event-list-test-key-1234567890123456";
    @Autowired private JdbcTemplate jdbc;
    private MockMvc mvc;
    private UUID concert;
    private String path;

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id)
                values ('Events',now()+interval '1 day',now(),?) returning id
                """, UUID.class, UUID.randomUUID());
        path = "/api/v1/admin/concerts/" + concert + "/events";
        mvc = MockMvcBuilders.standaloneSetup(new ConcertEventController(jdbc))
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(new AdminAuthenticationFilter(KEY)).build();
    }

    private void event(UUID id, UUID owner, boolean published) {
        jdbc.update("""
                insert into outgoing_events(id,event_type,payload,created_at,published_at,attempt_count,last_error)
                values (?,'ticket.purchased',?,now(),case when ? then now() else null end,2,'private error')
                """, id, "{\"concertId\":\"" + owner + "\",\"buyer\":\"private buyer\"}", published);
    }

    @Test
    void filtersConcertAndPaginatesTiedTimestampsWithoutExposingPayload() throws Exception {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        event(second, concert, false);
        event(first, concert, true);
        event(UUID.fromString("00000000-0000-0000-0000-000000000004"), UUID.randomUUID(), true);
        mvc.perform(get(path).param("limit", "1").header("X-Admin-Key", KEY)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.items[0].publishAttempts").value(2))
                .andExpect(jsonPath("$.items[0].publishedAt").isNotEmpty())
                .andExpect(jsonPath("$.hasMore").value(true)).andExpect(jsonPath("$.nextOffset").value(1))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        mvc.perform(get(path).param("limit", "1").param("offset", "1").header("X-Admin-Key", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(second.toString()))
                .andExpect(jsonPath("$.items[0].publishedAt").isEmpty())
                .andExpect(jsonPath("$.hasMore").value(false)).andExpect(jsonPath("$.nextOffset").isEmpty());
    }

    @Test
    void distinguishesEmptyConcertMissingConcertAndUnauthorizedRequest() throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Admin-Key", KEY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get(path.replace(concert.toString(), UUID.randomUUID().toString())).header("X-Admin-Key", KEY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONCERT_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=101", "offset=-1", "offset=50001", "limit=invalid"})
    void rejectsInvalidPages(String query) throws Exception {
        String[] parts = query.split("=");
        mvc.perform(get(path).param(parts[0], parts[1]).header("X-Admin-Key", KEY))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
    }
}
