package com.lazorenkoo.eventrush.reservation;

import com.lazorenkoo.eventrush.admission.AdmissionSessionStore;
import com.lazorenkoo.eventrush.admission.SessionCookie;
import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient.Admission;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
class ReservationReadIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdmissionSessionStore sessions;
    @Autowired
    private ReservationReadController controller;

    private final UUID owner = UUID.randomUUID();
    private MockMvc mvc;
    private UUID reservation;
    private String token;

    @BeforeEach
    void setup() {
        UUID gate = UUID.randomUUID();
        UUID seat = jdbc.queryForObject("""
                with concert as (
                    insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id)
                    values ('Read show', now() + interval '1 day', now(), ?) returning id
                )
                insert into seats (concert_id, section, seat_row, seat_number, price)
                select id, 'A', '1', 1, 10 from concert returning id
                """, UUID.class, gate);
        reservation = jdbc.queryForObject("""
                insert into reservations (user_id, seat_id, expires_at)
                values (?, ?, now() + interval '5 minutes') returning id
                """, UUID.class, owner, seat);
        token = sessions.create(new Admission(gate, owner, UUID.randomUUID(), gate + ":" + "a".repeat(43)),
                Instant.now().plusSeconds(60));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void ownerCanReadAfterGateAdmissionExpiresWithoutExposingIdentity() throws Exception {
        jdbc.update("update admission_sessions set admission_expires_at = now() - interval '1 second' where owner_id = ?",
                owner);
        mvc.perform(authenticated(reservation)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(reservation.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.sessionCredential").doesNotExist());
    }

    @Test
    void foreignAndMissingReservationsAreIndistinguishableEvenWithForgedOwner() throws Exception {
        UUID foreignOwner = UUID.randomUUID();
        jdbc.update("update reservations set user_id = ? where id = ?", foreignOwner, reservation);
        mvc.perform(authenticated(reservation).param("userId", foreignOwner.toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        mvc.perform(authenticated(UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    void missingAndRevokedApplicationSessionsCannotRead() throws Exception {
        mvc.perform(get("/api/v1/reservations/" + reservation)).andExpect(status().isUnauthorized());
        sessions.revoke(token);
        mvc.perform(authenticated(reservation)).andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder authenticated(UUID id) {
        return get("/api/v1/reservations/" + id).cookie(new Cookie(SessionCookie.NAME, token));
    }
}
