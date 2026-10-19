package com.lazorenkoo.eventrush.admission;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdmissionControllerTest {

    private final AdmissionService service = mock(AdmissionService.class);
    private final AdmissionSessionStore sessions = mock(AdmissionSessionStore.class);
    private final UUID concert = UUID.randomUUID();
    private final UUID visitor = UUID.randomUUID();
    private final String token = "a".repeat(43);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AdmissionController(service, sessions, new SessionCookie(true)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JsonMapper.builder()
                        .findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
                .addFilters(new BrowserOriginFilter("https://tickets.example.com")).build();
    }

    @Test
    void exchangeReturnsTokenOnlyAsSecureCookieAndDisablesCaching() throws Exception {
        when(service.exchange(concert, visitor, "signed-token")).thenReturn(token);
        mvc.perform(exchange().cookie(new Cookie("cg_user_id", visitor.toString())))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(cookie().value(SessionCookie.NAME, token))
                .andExpect(cookie().httpOnly(SessionCookie.NAME, true))
                .andExpect(cookie().secure(SessionCookie.NAME, true))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")));
        verify(service).exchange(concert, visitor, "signed-token");
    }

    @Test
    void missingOrDuplicateVisitorNeverReachesAdmissionService() throws Exception {
        mvc.perform(exchange()).andExpect(status().isUnauthorized());
        mvc.perform(exchange().cookie(new Cookie("cg_user_id", visitor.toString()),
                        new Cookie("cg_user_id", visitor.toString())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void upstreamRejectionNeverSetsApplicationCookie() throws Exception {
        when(service.exchange(concert, visitor, "signed-token"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMISSION_REJECTED"));
        mvc.perform(exchange().cookie(new Cookie("cg_user_id", visitor.toString())))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void heartbeatRequiresSessionAndReturnsOnlyExpiry() throws Exception {
        mvc.perform(api("heartbeat")).andExpect(status().isUnauthorized());
        Instant expiry = Instant.parse("2026-10-20T12:00:00Z");
        when(service.heartbeat(token)).thenReturn(expiry);
        mvc.perform(api("heartbeat").cookie(new Cookie(SessionCookie.NAME, token)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"expiresAt\":\"2026-10-20T12:00:00Z\"}", true));
        verify(service).heartbeat(token);
    }

    @Test
    void logoutRevokesAndClearsCookieWhileOriginGuardBlocksForeignRequests() throws Exception {
        mvc.perform(post("/api/v1/admission/logout").cookie(new Cookie(SessionCookie.NAME, token)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sessions);
        mvc.perform(api("logout").cookie(new Cookie(SessionCookie.NAME, token)))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge(SessionCookie.NAME, 0))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(sessions).revoke(token);
    }

    @Test
    void invalidExchangeBodyIsRejectedBeforeServiceCall() throws Exception {
        mvc.perform(api("exchange").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private MockHttpServletRequestBuilder exchange() {
        return api("exchange").contentType(MediaType.APPLICATION_JSON)
                .content("{\"concertId\":\"" + concert + "\",\"admissionToken\":\"signed-token\"}");
    }

    private MockHttpServletRequestBuilder api(String path) {
        return post("/api/v1/admission/" + path).header("Origin", "https://tickets.example.com");
    }
}
