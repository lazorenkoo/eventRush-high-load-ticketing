package com.lazorenkoo.eventrush.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "eventrush.admin.api-key=journal-test-key-1234567890123456"
})
@AutoConfigureMockMvc
@Transactional
class ParticipantJournalIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    private UUID concert;
    private UUID event;
    private long first;
    private long last;

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id)
                values ('Journal',now()+interval '1 day',now(),?) returning id
                """, UUID.class, UUID.randomUUID());
        UUID seat = jdbc.queryForObject("""
                insert into seats(concert_id,section,seat_row,seat_number,price)
                values (?,'Floor','A',300,25) returning id
                """, UUID.class, concert);
        UUID participant = jdbc.queryForObject("""
                insert into purchase_participants(user_id,concert_id,preferred_seat_id,first_name,last_name,source)
                values (?,?,?,'Alice','Smith','MANUAL') returning id
                """, UUID.class, UUID.randomUUID(), concert, seat);
        event = UUID.randomUUID();
        first = append(participant, "REGISTRATION", "REGISTERED");
        last = append(participant, "QUEUE", "WAITING");
    }

    private long append(UUID participant, String kind, String state) {
        return jdbc.queryForObject("""
                insert into participant_activity(participant_id,event_id,kind,state) values (?,?,?,?) returning id
                """, Long.class, participant, event, kind, state);
    }

    private MockHttpServletRequestBuilder request() {
        return get("/api/v1/admin/concerts/" + concert + "/journal")
                .header("X-Admin-Key", "journal-test-key-1234567890123456");
    }

    @Test
    void protectsNamesAndKeepsAscendingCursorAcrossPages() throws Exception {
        mvc.perform(get("/api/v1/admin/concerts/" + concert + "/journal" )).andExpect(status().isUnauthorized());
        mvc.perform(request().param("limit", "1")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].firstName").value("Alice"))
                .andExpect(jsonPath("$.items[0].kind").value("REGISTRATION"))
                .andExpect(jsonPath("$.items[0].userId").doesNotExist())
                .andExpect(jsonPath("$.nextAfter").value(first)).andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(request().param("after", Long.toString(first))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.nextAfter").value(last))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(request().param("after", Long.toString(last))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.nextAfter").value(last));
    }

    @ParameterizedTest
    @CsvSource({"q,alice,2", "q,300,2", "q,%,0", "q,_,0", "q,x' OR 1=1,0",
            "source,TEST,0", "source,MANUAL,2", "kind,QUEUE,1", "kind,PAYMENT,0"})
    void filtersAndSearchesLiteralValues(String key, String value, int count) throws Exception {
        mvc.perform(request().param(key, value)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(count));
    }

    @Test
    void searchesEventIdentityAndScopesConcerts() throws Exception {
        mvc.perform(request().param("q", event.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/v1/admin/concerts/" + UUID.randomUUID() + "/journal")
                .header("X-Admin-Key", "journal-test-key-1234567890123456")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void boundsSearchLength() throws Exception {
        mvc.perform(request().param("q", "a".repeat(101))).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @CsvSource({"after,-1", "limit,0", "limit,101", "source,OTHER", "kind,UNKNOWN", "after,invalid"})
    void rejectsInvalidQueries(String key, String value) throws Exception {
        mvc.perform(request().param(key, value)).andExpect(status().isBadRequest());
    }
}