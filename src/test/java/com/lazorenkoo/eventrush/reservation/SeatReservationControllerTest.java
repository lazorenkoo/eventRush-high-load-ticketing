package com.lazorenkoo.eventrush.reservation;

import com.lazorenkoo.eventrush.admission.*;
import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient.Admission;
import com.lazorenkoo.eventrush.purchase.ParticipantReservationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SeatReservationControllerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AdmissionSessionStore sessions = mock(AdmissionSessionStore.class);
    private final AdmissionService admissions = mock(AdmissionService.class);
    private final ReservationService reservations = mock(ReservationService.class);
    private final UUID seat = UUID.randomUUID(), concert = UUID.randomUUID(), gate = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final String token = "a".repeat(43);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var admission = new Admission(gate, owner, UUID.randomUUID(), gate + ":" + "a".repeat(43));
        when(sessions.require(token)).thenReturn(new AdmissionSessionStore.Session(admission,
                Instant.now().plusSeconds(60), Instant.now().plusSeconds(3600)));
        when(jdbc.queryForList("select concert_id from seats where id = ?", UUID.class, seat)).thenReturn(List.of(concert));
        when(admissions.requireActiveOwner(token, concert)).thenReturn(owner);
        mvc = MockMvcBuilders.standaloneSetup(new SeatReservationController(jdbc, new SessionCookie(true),
                        sessions, admissions, reservations, mock(ParticipantReservationService.class))).setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new BrowserOriginFilter("https://tickets.example.com")).build();
    }

    @Test
    void reservationUsesServerOwnerDespiteForgedUserParameter() throws Exception {
        UUID id = UUID.randomUUID();
        when(reservations.reserve(seat, owner, gate)).thenReturn(new ReservationService.Reservation(id, owner,
                seat, "ACTIVE", Instant.now(), Instant.now().plusSeconds(300)));
        mvc.perform(authenticated().param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.userId").doesNotExist());
        var order = inOrder(sessions, jdbc, admissions, reservations);
        order.verify(sessions).require(token);
        order.verify(jdbc).queryForList("select concert_id from seats where id = ?", UUID.class, seat);
        order.verify(admissions).requireActiveOwner(token, concert);
        order.verify(reservations).reserve(seat, owner, gate);
    }

    @Test
    void missingOrRevokedSessionIsRejectedBeforeLookingUpSeat() throws Exception {
        mvc.perform(request()).andExpect(status().isUnauthorized());
        when(sessions.require(token)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mvc.perform(authenticated()).andExpect(status().isUnauthorized());
        verifyNoInteractions(jdbc, admissions, reservations);
    }

    @Test
    void rejectedAdmissionPreventsReservation() throws Exception {
        when(admissions.requireActiveOwner(token, concert))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMISSION_GATE_MISMATCH"));
        mvc.perform(authenticated()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMISSION_GATE_MISMATCH"));
        verifyNoInteractions(reservations);
    }

    @Test
    void unavailableSeatRetainsConflictAndMissingSeatReturnsNotFound() throws Exception {
        when(reservations.reserve(seat, owner, gate))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "SEAT_ALREADY_RESERVED"));
        mvc.perform(authenticated()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_RESERVED"));
        when(jdbc.queryForList("select concert_id from seats where id = ?", UUID.class, seat)).thenReturn(List.of());
        mvc.perform(authenticated()).andExpect(status().isNotFound());
        verify(reservations, times(1)).reserve(seat, owner, gate);
    }

    @Test
    void missingOriginCannotReachBusinessServices() throws Exception {
        mvc.perform(post("/api/v1/seats/" + seat + "/reserve").cookie(new Cookie(SessionCookie.NAME, token)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sessions, jdbc, admissions, reservations);
    }

    private MockHttpServletRequestBuilder request() {
        return post("/api/v1/seats/" + seat + "/reserve").header("Origin", "https://tickets.example.com");
    }

    private MockHttpServletRequestBuilder authenticated() {
        return request().cookie(new Cookie(SessionCookie.NAME, token));
    }
}
