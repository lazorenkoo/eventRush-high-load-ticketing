package com.lazorenkoo.eventrush.concert;

import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
@Transactional
class ConcertReadIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ConcertReadController controller;

    private final UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID draft = UUID.randomUUID();
    private final UUID gate = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        insert(first, gate, "ON_SALE");
        insert(second, UUID.randomUUID(), "SOLD_OUT");
        insert(draft, UUID.randomUUID(), "DRAFT");
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void publicListHidesDraftsAndUsesStablePagination() throws Exception {
        mvc.perform(get("/api/v1/concerts")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first.toString()))
                .andExpect(jsonPath("$[1].id").value(second.toString()));
        mvc.perform(get("/api/v1/concerts").param("limit", "1").param("offset", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(second.toString()));
        mvc.perform(get("/api/v1/concerts").param("offset", "2"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void detailExposesQueueGateButDraftAndMissingConcertsReturnSame404() throws Exception {
        mvc.perform(get("/api/v1/concerts/" + first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.crowdgateGateId").value(gate.toString()))
                .andExpect(jsonPath("$.status").value("ON_SALE"))
                .andExpect(header().string("Cache-Control", "no-store"));
        for (UUID id : new UUID[]{draft, UUID.randomUUID()}) {
            mvc.perform(get("/api/v1/concerts/" + id)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CONCERT_NOT_FOUND"));
        }
    }

    @ParameterizedTest
    @CsvSource({"0,0", "101,0", "20,-1", "20,10001"})
    void rejectsUnboundedOrInvalidPagination(int limit, int offset) throws Exception {
        mvc.perform(get("/api/v1/concerts").param("limit", Integer.toString(limit))
                        .param("offset", Integer.toString(offset)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
    }

    private void insert(UUID id, UUID gateId, String status) {
        jdbc.update("""
                insert into concerts (id, name, starts_at, sale_starts_at, status, crowdgate_gate_id)
                values (?, 'Read show', current_timestamp + interval '1 day', current_timestamp, ?, ?)
                """, id, status, gateId);
    }
}
