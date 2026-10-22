package com.lazorenkoo.eventrush.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazorenkoo.eventrush.admission.BrowserOriginFilter;
import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
class ConcertCreationIntegrationTest {

    private static final String KEY = "test-only-admin-key-" + "a".repeat(32);
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ConcertCreationController controller;
    @Autowired
    private ObjectMapper mapper;

    private final UUID gate = UUID.randomUUID();
    private final Instant start = Instant.now().plusSeconds(3600);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new BrowserOriginFilter("https://tickets.example.com"), new AdminAuthenticationFilter(KEY))
                .build();
    }

    @AfterEach
    void removeCommittedConcert() {
        jdbc.update("delete from concerts where crowdgate_gate_id = ?", gate);
    }

    @Test
    void createsTrimmedDraftAndRejectsDuplicateGateWithoutSecondConcert() throws Exception {
        String body = body(" Show ", start, start.minusSeconds(60));
        mvc.perform(authorized().content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Show")).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.crowdgateGateId").value(gate.toString()))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(authorized().content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CROWDGATE_GATE_ALREADY_ASSIGNED"));
        assertThat(jdbc.queryForObject("select status from concerts where crowdgate_gate_id = ?",
                String.class, gate)).isEqualTo("DRAFT");
    }

    @Test
    void rejectsInvalidNamePastStartAndReversedSaleWindow() throws Exception {
        for (String body : new String[]{body(" ", start, start), body("x".repeat(256), start, start),
                body("Show", Instant.EPOCH, Instant.EPOCH), body("Show", start, start.plusSeconds(1)), "{}"}) {
            mvc.perform(authorized().content(body)).andExpect(status().isBadRequest());
        }
        assertNoConcert();
    }

    @Test
    void deniedAdminOrOriginCannotCreateConcert() throws Exception {
        String body = body("Show", start, start.minusSeconds(60));
        mvc.perform(post("/api/v1/admin/concerts").header("Origin", "https://tickets.example.com")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/concerts").header("X-Admin-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        assertNoConcert();
    }

    private String body(String name, Instant startsAt, Instant saleStartsAt) throws Exception {
        return mapper.writeValueAsString(Map.of("name", name, "startsAt", startsAt,
                "saleStartsAt", saleStartsAt, "crowdgateGateId", gate));
    }

    private MockHttpServletRequestBuilder authorized() {
        return post("/api/v1/admin/concerts").header("Origin", "https://tickets.example.com")
                .header("X-Admin-Key", KEY).contentType(MediaType.APPLICATION_JSON);
    }

    private void assertNoConcert() {
        assertThat(jdbc.queryForObject("select count(*) from concerts where crowdgate_gate_id = ?",
                Integer.class, gate)).isZero();
    }
}
