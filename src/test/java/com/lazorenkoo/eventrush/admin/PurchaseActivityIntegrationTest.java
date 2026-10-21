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
        "eventrush.admin.api-key=activity-test-key-1234567890123456"
})
@AutoConfigureMockMvc
@Transactional
class PurchaseActivityIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    private UUID concert;
    private UUID seat;
    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID bob = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id)
                values ('Activity test',now()+interval '1 day',now(),?) returning id
                """, UUID.class, UUID.randomUUID());
        seat = jdbc.queryForObject("""
                insert into seats(concert_id,section,seat_row,seat_number,price)
                values (?,'Floor','A',300,25) returning id
                """, UUID.class, concert);
        participant(alice, "Alice", "MANUAL", null);
        participant(bob, "Bob", "TEST", UUID.randomUUID());
    }

    private void participant(UUID id, String first, String source, UUID run) {
        jdbc.update("""
                insert into purchase_participants(id,user_id,concert_id,preferred_seat_id,
                    first_name,last_name,source,run_id)
                values (?,?,?, ?,?,'Visitor',?,?)
                """, id, UUID.randomUUID(), concert, seat, first, source, run);
    }

    private MockHttpServletRequestBuilder request() {
        return get("/api/v1/admin/concerts/" + concert + "/activity")
                .header("X-Admin-Key", "activity-test-key-1234567890123456");
    }

    @Test
    void requiresAdministrativeKeyBeforeExposingNames() throws Exception {
        mvc.perform(get("/api/v1/admin/concerts/" + concert + "/activity"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$[0]").doesNotExist());
    }

    @Test
    void returnsSafeSnapshotWithDeterministicPagination() throws Exception {
        mvc.perform(request().param("limit", "1")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].id").value(bob.toString()))
                .andExpect(jsonPath("$[0].state").value("REGISTERED"))
                .andExpect(jsonPath("$[0].userId").doesNotExist())
                .andExpect(jsonPath("$[0].queueKey").doesNotExist());
        mvc.perform(request().param("limit", "1").param("offset", "1"))
                .andExpect(jsonPath("$[0].id").value(alice.toString()));
    }

    @Test
    void searchesNamesSeatsAndOperationIdsLiterally() throws Exception {
        for (String query : new String[]{"aLiCe", alice.toString()}) {
            mvc.perform(request().param("q", query)).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(alice.toString()));
        }
        mvc.perform(request().param("q", "Floor A 300")).andExpect(jsonPath("$.length()").value(2));
        for (String query : new String[]{"%", "_", "' OR true --"}) {
            mvc.perform(request().param("q", query)).andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Test
    void filtersSourceStateAndConcert() throws Exception {
        mvc.perform(request().param("source", "TEST").param("state", "REGISTERED"))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].firstName").value("Bob"));
        mvc.perform(request().param("state", "PURCHASED")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/admin/concerts/" + UUID.randomUUID() + "/activity")
                        .header("X-Admin-Key", "activity-test-key-1234567890123456"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @ParameterizedTest
    @CsvSource({"limit,0", "limit,101", "offset,-1", "offset,50001", "source,OTHER", "state,WAITING"})
    void rejectsInvalidQueryBounds(String field, String value) throws Exception {
        mvc.perform(request().param(field, value)).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnboundedSearchText() throws Exception {
        mvc.perform(request().param("q", "x".repeat(101))).andExpect(status().isBadRequest());
    }
}
