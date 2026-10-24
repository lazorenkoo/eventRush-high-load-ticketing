package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.load.LoadClientLeases;
import com.lazorenkoo.eventrush.load.LoadRunImportService;
import com.lazorenkoo.eventrush.load.LoadRunLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///load_control_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "eventrush.admin.api-key=control-test-key-1234567890123456"
})
@AutoConfigureMockMvc
@Transactional
class LoadRunControlIntegrationTest {
    private static final String KEY = "control-test-key-1234567890123456";
    private static final String ORIGIN = "http://localhost:8082";
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private LoadRunImportService imports;
    @Autowired private LoadRunLifecycleService lifecycle;
    @Autowired private LoadClientLeases leases;
    private UUID concert;
    private UUID run;
    private String path;

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id,status)
                values ('Control',now()+interval '1 day',now(),?,'ON_SALE') returning id
                """, UUID.class, UUID.randomUUID());
        jdbc.update("insert into seats(concert_id,section,seat_row,seat_number,price) values(?,'A','1',1,25)", concert);
        byte[] csv = ("firstName,lastName,seatNumber\n" + "First,Last,1\n".repeat(6)).getBytes(StandardCharsets.UTF_8);
        run = imports.importCsv(concert, 2, new ByteArrayInputStream(csv)).id();
        path = "/api/v1/admin/concerts/" + concert + "/load-runs/" + run;
    }

    @Test
    void reportsAllClientStatesWithExplicitRunScope() throws Exception {
        String[] states = {"PENDING", "RUNNING", "SUCCEEDED", "SOLD_OUT", "FAILED", "CANCELLED"};
        for (int index = 0; index < states.length; index++) {
            String state = states[index];
            boolean active = state.equals("PENDING") || state.equals("RUNNING");
            jdbc.update("""
                    update load_test_clients set status=?,finished_at=case when ? then null else clock_timestamp() end,
                        lease_token=case when ? then gen_random_uuid() else null end,
                        lease_expires_at=case when ? then clock_timestamp()+interval '60 seconds' else null end
                    where run_id=? and ordinal=?
                    """, state, active, state.equals("RUNNING"), state.equals("RUNNING"), run, index + 1);
        }
        mvc.perform(get(path + "/progress").header("X-Admin-Key", KEY)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.scope").value("TEST_RUN"))
                .andExpect(jsonPath("$.participantCount").value(6)).andExpect(jsonPath("$.concurrency").value(2))
                .andExpect(jsonPath("$.pending").value(1)).andExpect(jsonPath("$.running").value(1))
                .andExpect(jsonPath("$.succeeded").value(1)).andExpect(jsonPath("$.unavailable").value(1))
                .andExpect(jsonPath("$.failed").value(1)).andExpect(jsonPath("$.cancelled").value(1))
                .andExpect(jsonPath("$.firstName").doesNotExist()).andExpect(jsonPath("$.leaseToken").doesNotExist());
    }

    @Test
    void readyStopIsIdempotentAndCancelsEveryPendingClient() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(path + "/stop").header("X-Admin-Key", KEY).header("Origin", ORIGIN))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.status").value("STOPPED"));
        }
        mvc.perform(get(path + "/progress").header("X-Admin-Key", KEY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(0)).andExpect(jsonPath("$.cancelled").value(6));
    }

    @Test
    void runningStopWaitsForReaperBeforeReportingStopped() throws Exception {
        lifecycle.start(concert, run);
        mvc.perform(post(path + "/stop").header("X-Admin-Key", KEY).header("Origin", ORIGIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STOPPING"));
        leases.reap(run);
        mvc.perform(get(path + "/progress").header("X-Admin-Key", KEY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED")).andExpect(jsonPath("$.cancelled").value(6));
    }

    @Test
    void requiresAdminKeyAndOriginAndRejectsForeignConcert() throws Exception {
        mvc.perform(get(path + "/progress")).andExpect(status().isUnauthorized());
        mvc.perform(post(path + "/stop").header("Origin", ORIGIN)).andExpect(status().isUnauthorized());
        mvc.perform(post(path + "/stop").header("X-Admin-Key", KEY)).andExpect(status().isForbidden());
        String foreign = path.replace(concert.toString(), UUID.randomUUID().toString());
        mvc.perform(get(foreign + "/progress").header("X-Admin-Key", KEY)).andExpect(status().isNotFound());
        mvc.perform(post(foreign + "/stop").header("X-Admin-Key", KEY).header("Origin", ORIGIN))
                .andExpect(status().isNotFound());
    }
}
