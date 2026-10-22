package com.lazorenkoo.eventrush.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///upload_http_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "eventrush.admin.api-key=upload-test-key-1234567890123456"
})
@AutoConfigureMockMvc
@Transactional
class LoadRunControllerIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    private UUID concert;
    private String path;
    private static final String KEY = "upload-test-key-1234567890123456";
    private static final String CSV = "firstName,lastName,seatNumber\nAlice,Smith,300\n";

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id)
                values ('Upload',now()+interval '1 day',now(),?) returning id
                """, UUID.class, UUID.randomUUID());
        jdbc.update("insert into seats(concert_id,section,seat_row,seat_number,price) values(?,'A','1',300,25)", concert);
        path = "/api/v1/admin/concerts/" + concert + "/load-runs";
    }

    private MockMultipartFile file(String csv) {
        return new MockMultipartFile("file", "clients.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartHttpServletRequestBuilder upload() {
        var request = multipart(path);
        request.header("Origin", "http://localhost:8082").header("X-Admin-Key", KEY);
        return request;
    }

    @Test
    void uploadReturnsReadyMetadataWithoutStartingClients() throws Exception {
        mvc.perform(upload().file(file(CSV))).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.participantCount").value(1)).andExpect(jsonPath("$.status").value("READY"));
        UUID run = jdbc.queryForObject("select id from load_test_runs where concert_id=?", UUID.class, concert);
        mvc.perform(get(path + "/" + run).header("X-Admin-Key", KEY)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.firstName").doesNotExist());
        assertThat(jdbc.queryForObject("select status from load_test_clients where run_id=?", String.class, run))
                .isEqualTo("PENDING");
        mvc.perform(get("/api/v1/admin/concerts/" + UUID.randomUUID() + "/load-runs/" + run)
                .header("X-Admin-Key", KEY)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsMissingKeyOriginAndFile() throws Exception {
        mvc.perform(multipart(path).file(file(CSV)).header("Origin", "http://localhost:8082"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart(path).file(file(CSV)).header("X-Admin-Key", KEY)).andExpect(status().isForbidden());
        mvc.perform(upload()).andExpect(status().isBadRequest());
        mvc.perform(get(path + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get(path + "/" + UUID.randomUUID()).header("X-Admin-Key", KEY)).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "wrong header\nA,B,300", "firstName,lastName,seatNumber\nA,B,301"})
    void rejectsInvalidFilesWithoutPersistingRun(String csv) throws Exception {
        mvc.perform(upload().file(file(csv))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from load_test_runs where concert_id=?",
                Integer.class, concert)).isZero();
    }

    @Test
    void rejectsControllerSizeAndConcurrencyBounds() throws Exception {
        var oversized = new MockMultipartFile("file", new byte[] {1}) {
            @Override public long getSize() { return 16 * 1024 * 1024 + 1; }
        };
        mvc.perform(upload().file(oversized)).andExpect(status().isBadRequest());
        mvc.perform(upload().file(file(CSV)).param("concurrency", "101")).andExpect(status().isBadRequest());
    }
}