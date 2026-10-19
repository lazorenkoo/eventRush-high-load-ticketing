package com.lazorenkoo.eventrush.api;

import com.lazorenkoo.eventrush.admission.BrowserOriginFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new BrowserOriginFilter("https://tickets.example.com")).build();
    }

    @Test
    void preservesSafeBusinessCodeAndSanitizesUnsafeReason() throws Exception {
        mvc.perform(get("/api/probe/business")).andExpect(status().isConflict())
                .andExpect(content().json("{\"status\":409,\"code\":\"SEAT_ALREADY_RESERVED\"}", true))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/probe/unsafe")).andExpect(status().isForbidden())
                .andExpect(content().json("{\"status\":403,\"code\":\"FORBIDDEN\"}", true));
    }

    @Test
    void validationAndMalformedJsonNeverEchoCredentials() throws Exception {
        for (String body : new String[]{"{\"token\":\"private-token-value\"}", "{\"token\":\"private-token-value"}) {
            mvc.perform(post("/api/probe/input").header("Origin", "https://tickets.example.com")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"status\":400,\"code\":\"INVALID_REQUEST\"}", true))
                    .andExpect(content().string(not(containsString("private-token-value"))))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test
    void typeMismatchAndMethodErrorsKeepCorrectStatusAndHeaders() throws Exception {
        mvc.perform(get("/api/probe/id/invalid-uuid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/probe/input")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void internalErrorAndOriginRejectionUseSanitizedJson() throws Exception {
        mvc.perform(get("/api/probe/crash")).andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"status\":500,\"code\":\"INTERNAL_ERROR\"}", true))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/probe/input")).andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"status\":403,\"code\":\"ORIGIN_REJECTED\"}", true))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @RestController
    static class Probe {
        @GetMapping("/api/probe/business")
        void business() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SEAT_ALREADY_RESERVED");
        }

        @GetMapping("/api/probe/unsafe")
        void unsafe() {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "private-token-value");
        }

        @GetMapping("/api/probe/crash")
        void crash() {
            throw new IllegalStateException("private-database-password");
        }

        @PostMapping("/api/probe/input")
        void input(@Valid @RequestBody Input input) {
        }

        @GetMapping("/api/probe/id/{id}")
        void id(@PathVariable UUID id) {
        }
    }

    record Input(@Size(max = 3) String token) {
    }
}
