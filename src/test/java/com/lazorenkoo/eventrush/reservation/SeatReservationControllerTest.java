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
    private final ParticipantReservationService namedReservations = mock(ParticipantReservationService.class);
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
                        sessions, admissions, reservations, namedReservations)).setControllerAdvice(new ApiExceptionHandler())
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

    @Test
    void namedRequestUsesAdmittedIdentityAndNeverFallsBackToBareReserve() throws Exception {
        UUID participant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(namedReservations.reserve(participant, seat, owner, gate)).thenReturn(
                new ReservationService.Reservation(id, owner, seat, "ACTIVE", Instant.now(),
                        Instant.now().plusSeconds(300)));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(authenticated().param("participantId", participant.toString())
                            .param("userId", UUID.randomUUID().toString()).param("gateId", UUID.randomUUID().toString()))
                    .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.id").value(id.toString()))
                    .andExpect(jsonPath("$.userId").doesNotExist())
                    .andExpect(jsonPath("$.participantId").doesNotExist());
        }
        verify(namedReservations, times(2)).reserve(participant, seat, owner, gate);
        verifyNoInteractions(reservations);
    }

    @Test
    void namedRequestRequiresSessionAndLiveAdmission() throws Exception {
        UUID participant = UUID.randomUUID();
        mvc.perform(request().param("participantId", participant.toString())).andExpect(status().isUnauthorized());
        when(admissions.requireActiveOwner(token, concert))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ADMISSION_EXPIRED"));
        mvc.perform(authenticated().param("participantId", participant.toString()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(namedReservations, reservations);
    }

    @Test
    void foreignParticipantRejectionCannotCreateUnlinkedReservation() throws Exception {
        UUID participant = UUID.randomUUID();
        when(namedReservations.reserve(participant, seat, owner, gate))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND"));
        mvc.perform(authenticated().param("participantId", participant.toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PARTICIPANT_NOT_FOUND"));
        verifyNoInteractions(reservations);
    }

    @Test
    void invalidParticipantIdIsRejectedBeforeBusinessCalls() throws Exception {
        mvc.perform(authenticated().param("participantId", "invalid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sessions, jdbc, admissions, namedReservations, reservations);
    }

    @Test
    void namedRequestWithoutOriginCannotReachServices() throws Exception {
        mvc.perform(post("/api/v1/seats/" + seat + "/reserve")
                        .cookie(new Cookie(SessionCookie.NAME, token))
                        .param("participantId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sessions, jdbc, admissions, namedReservations, reservations);
    }

    private MockHttpServletRequestBuilder request() {
        return post("/api/v1/seats/" + seat + "/reserve").header("Origin", "https://tickets.example.com");
    }

    private MockHttpServletRequestBuilder authenticated() {
        return request().cookie(new Cookie(SessionCookie.NAME, token));
    }
}
