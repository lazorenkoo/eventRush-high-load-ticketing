package com.lazorenkoo.eventrush.admin;

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
class SeatCreationIntegrationTest {

    private static final String KEY = "test-only-admin-key-" + "a".repeat(32);
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SeatCreationController controller;

    private UUID concert;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id)
                values ('Seat setup', now() + interval '1 day', now(), ?) returning id
                """, UUID.class, UUID.randomUUID());
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new BrowserOriginFilter("https://tickets.example.com"), new AdminAuthenticationFilter(KEY))
                .build();
    }

    @AfterEach
    void removeCommittedFixtures() {
        jdbc.update("delete from seats where concert_id = ?", concert);
        jdbc.update("delete from concerts where id = ?", concert);
    }

    @Test
    void createsAvailableSeatsAndRollsBackWholeConflictingBatch() throws Exception {
        mvc.perform(authorized().content(batch(seat(1)))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(authorized().content(batch(seat(2) + "," + seat(1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEAT_POSITION_ALREADY_EXISTS"));
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from seats where concert_id = ?", String.class, concert))
                .isEqualTo("AVAILABLE");
    }

    @Test
    void rejectsInventoryChangesOutsideDraft() throws Exception {
        jdbc.update("update concerts set status = 'ON_SALE' where id = ?", concert);
        mvc.perform(authorized().content(batch(seat(1)))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCERT_NOT_DRAFT"));
        assertThat(count()).isZero();
    }

    @Test
    void validatesEveryEntryAndBatchLimitBeforeMutation() throws Exception {
        String tooMany = String.join(",", java.util.Collections.nCopies(101, seat(1)));
        for (String body : new String[]{batch(""), batch("null"), batch(seat(0)),
                batch(seat(1).replace("10.00", "-1")), batch(seat(1).replace("10.00", "1.001")), batch(tooMany)}) {
            mvc.perform(authorized().content(body)).andExpect(status().isBadRequest());
        }
        assertThat(count()).isZero();
    }

    @Test
    void unauthorizedRequestCannotCreateSeats() throws Exception {
        mvc.perform(post("/api/v1/admin/concerts/" + concert + "/seats")
                        .header("Origin", "https://tickets.example.com").contentType(MediaType.APPLICATION_JSON)
                        .content(batch(seat(1)))).andExpect(status().isUnauthorized());
        assertThat(count()).isZero();
    }

    private MockHttpServletRequestBuilder authorized() {
        return post("/api/v1/admin/concerts/" + concert + "/seats").header("Origin", "https://tickets.example.com")
                .header("X-Admin-Key", KEY).contentType(MediaType.APPLICATION_JSON);
    }

    private String seat(int number) {
        return "{\"section\":\" A \",\"row\":\"1\",\"number\":" + number + ",\"price\":10.00}";
    }

    private String batch(String entries) {
        return "{\"seats\":[" + entries + "]}";
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from seats where concert_id = ?", Integer.class, concert);
    }
}
