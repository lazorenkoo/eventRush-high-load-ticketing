package com.lazorenkoo.eventrush.purchase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazorenkoo.eventrush.admission.BrowserOriginFilter;
import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ParticipantControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID visitor = UUID.randomUUID();
    private final UUID concert = UUID.randomUUID();
    private final UUID seat = UUID.randomUUID();
    private final String token = UUID.randomUUID() + ":" + UUID.randomUUID();
    private ParticipantRegistrationService service;
    private MockMvc mvc;
    private String body;

    @BeforeEach
    void setup() throws Exception {
        service = mock(ParticipantRegistrationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ParticipantController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new BrowserOriginFilter("http://localhost:8082")).build();
        body = mapper.writeValueAsString(new ParticipantController.Registration(concert, seat, token, "First", "Last"));
    }

    private MockHttpServletRequestBuilder request(String json) {
        return post("/api/v1/participants").header("Origin", "http://localhost:8082")
                .contentType("application/json").content(json);
    }

    private Cookie visitorCookie() { return new Cookie("cg_user_id", visitor.toString()); }

    @Test
    void returnsOnlyIdWithNoStoreAndCookieBoundVisitor() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.register(visitor, concert, seat, token, "First", "Last")).thenReturn(id);
        mvc.perform(request(body).cookie(visitorCookie())).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"id\":\"" + id + "\"}", true));
        verify(service).register(visitor, concert, seat, token, "First", "Last");
    }

    @Test
    void missingOrDuplicateVisitorCookieNeverInvokesService() throws Exception {
        mvc.perform(request(body)).andExpect(status().isUnauthorized());
        mvc.perform(request(body).cookie(visitorCookie(), visitorCookie())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "1-1-1-1-1", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"})
    void nonCanonicalCookieNeverInvokesService(String value) throws Exception {
        mvc.perform(request(body).cookie(new Cookie("cg_user_id", value))).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId", "source", "runId", "unexpected"})
    void rejectsCallerSelectedIdentityAndSource(String field) throws Exception {
        var json = mapper.readTree(body).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).put(field, "untrusted");
        mvc.perform(request(json.toString()).cookie(visitorCookie())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"concertId", "seatId", "queueToken", "firstName", "lastName"})
    void rejectsMissingRequiredFields(String field) throws Exception {
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(body);
        json.remove(field);
        mvc.perform(request(json.toString()).cookie(visitorCookie())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsCrossOriginAndMissingOrigin() throws Exception {
        mvc.perform(post("/api/v1/participants").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(request(body).header("Origin", "https://foreign.example"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void mapsVerificationFailureAndHidesNamesAndToken() throws Exception {
        when(service.register(visitor, concert, seat, token, "First", "Last"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "CROWDGATE_QUEUE_REJECTED"));
        mvc.perform(request(body).cookie(visitorCookie())).andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"status\":401,\"code\":\"CROWDGATE_QUEUE_REJECTED\"}", true));
        String description = new ParticipantController.Registration(concert, seat, token, "First", "Last").toString();
        assertThat(description).doesNotContain(token, "First", "Last");
    }
}
