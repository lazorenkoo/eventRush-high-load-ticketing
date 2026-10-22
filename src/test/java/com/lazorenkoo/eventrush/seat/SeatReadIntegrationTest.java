package com.lazorenkoo.eventrush.seat;

import com.lazorenkoo.eventrush.admission.AdmissionService;
import com.lazorenkoo.eventrush.admission.SessionCookie;
import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
@Transactional
class SeatReadIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private final AdmissionService admissions = mock(AdmissionService.class);
    private final String token = "a".repeat(43);
    private UUID concert;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id)
                values ('Seat show', now() + interval '1 day', now(), ?) returning id
                """, UUID.class, UUID.randomUUID());
        jdbc.update("""
                insert into seats (concert_id, section, seat_row, seat_number, price, status)
                values (?, 'A', '1', 2, 20, 'SOLD'), (?, 'A', '1', 1, 10, 'AVAILABLE')
                """, concert, concert);
        when(admissions.requireActiveOwner(token, concert)).thenReturn(UUID.randomUUID());
        mvc = MockMvcBuilders.standaloneSetup(new SeatReadController(jdbc, admissions, new SessionCookie(true)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void listUsesStableSeatOrderAndReturnsOnlySafeFields() throws Exception {
        mvc.perform(authenticated()).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].number").value(1))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$[0].price").value(10))
                .andExpect(jsonPath("$[0].ownerId").doesNotExist())
                .andExpect(jsonPath("$[0].reservationId").doesNotExist());
        mvc.perform(authenticated().param("limit", "1").param("offset", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].number").value(2)).andExpect(jsonPath("$[0].status").value("SOLD"));
        verify(admissions, times(2)).requireActiveOwner(token, concert);
    }

    @Test
    void missingSessionAndRejectedGateCannotReadSeats() throws Exception {
        mvc.perform(get("/api/v1/concerts/" + concert + "/seats")).andExpect(status().isUnauthorized());
        verifyNoInteractions(admissions);
        when(admissions.requireActiveOwner(token, concert))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMISSION_GATE_MISMATCH"));
        mvc.perform(authenticated()).andExpect(status().isForbidden());
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "CROWDGATE_SESSION_EXPIRED"))
                .when(admissions).requireActiveOwner(token, concert);
        mvc.perform(authenticated()).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidBoundsAreRejectedWithoutRemoteCall() throws Exception {
        mvc.perform(authenticated().param("limit", "501")).andExpect(status().isBadRequest());
        mvc.perform(authenticated().param("offset", "-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(admissions);
    }

    private MockHttpServletRequestBuilder authenticated() {
        return get("/api/v1/concerts/" + concert + "/seats").cookie(new Cookie(SessionCookie.NAME, token));
    }
}
