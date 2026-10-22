package com.lazorenkoo.eventrush.reservation;

import com.lazorenkoo.eventrush.admission.*;
import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient.Admission;
import com.lazorenkoo.eventrush.payment.PaymentService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReservationActionsControllerTest {

    private final AdmissionSessionStore sessions = mock(AdmissionSessionStore.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final ReservationCancellationService cancellations = mock(ReservationCancellationService.class);
    private final UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
    private final String token = "a".repeat(43);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        UUID gate = UUID.randomUUID();
        when(sessions.require(token)).thenReturn(new AdmissionSessionStore.Session(
                new Admission(gate, owner, UUID.randomUUID(), gate + ":" + "a".repeat(43)),
                Instant.EPOCH, Instant.now().plusSeconds(3600)));
        mvc = MockMvcBuilders.standaloneSetup(new ReservationActionsController(sessions, new SessionCookie(true),
                        payments, cancellations)).setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new BrowserOriginFilter("https://tickets.example.com")).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUCCESS", "FAILED"})
    void paymentUsesServerOwnerAndReflectsAsynchronousState(String state) throws Exception {
        when(payments.start(id, owner, PaymentService.Mode.SUCCESS)).thenReturn(new PaymentService.Payment(
                UUID.randomUUID(), id, BigDecimal.TEN, state, PaymentService.Mode.SUCCESS, Instant.now(), Instant.now()));
        mvc.perform(authenticated("pay").param("userId", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"SUCCESS\"}"))
                .andExpect(status().is("PENDING".equals(state) ? 202 : 200))
                .andExpect(jsonPath("$.status").value(state)).andExpect(header().string("Cache-Control", "no-store"));
        verify(payments).start(id, owner, PaymentService.Mode.SUCCESS);
    }

    @Test
    void cancelUsesOwnerAfterGateExpiryAndReturnsEmptyResponse() throws Exception {
        mvc.perform(authenticated("cancel").param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(cancellations).cancel(id, owner);
    }

    @Test
    void missingRevokedSessionAndForeignReservationAreRejected() throws Exception {
        mvc.perform(request("cancel")).andExpect(status().isUnauthorized());
        when(sessions.require(token)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mvc.perform(authenticated("cancel")).andExpect(status().isUnauthorized());
        verifyNoInteractions(cancellations, payments);
    }

    @Test
    void coreOwnershipAndTtlErrorsKeepTheirStatus() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND"))
                .when(cancellations).cancel(id, owner);
        mvc.perform(authenticated("cancel")).andExpect(status().isNotFound());
        when(payments.start(id, owner, PaymentService.Mode.SUCCESS))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "RESERVATION_NOT_ACTIVE"));
        mvc.perform(authenticated("pay").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"SUCCESS\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_ACTIVE"));
    }

    @Test
    void invalidModesAndOriginCannotReachPaymentService() throws Exception {
        for (String body : new String[]{"{}", "{\"mode\":\"REAL_PAYMENT\"}"}) {
            mvc.perform(authenticated("pay").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/reservations/" + id + "/cancel"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(payments, cancellations);
    }

    private MockHttpServletRequestBuilder request(String action) {
        return post("/api/v1/reservations/" + id + "/" + action).header("Origin", "https://tickets.example.com");
    }

    private MockHttpServletRequestBuilder authenticated(String action) {
        return request(action).cookie(new Cookie(SessionCookie.NAME, token));
    }
}
