package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.admission.BrowserOriginFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAuthenticationTest {

    private static final String KEY = "test-only-admin-key-" + "a".repeat(32);
    private final AdminAuthenticationFilter filter = new AdminAuthenticationFilter(KEY);

    @Test
    void requiresOneExactKeyAndReturnsSanitizedNoStoreFailure() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/concerts");
        rejected(filter, request);
        request.addHeader("X-Admin-Key", "wrong-key");
        rejected(filter, request);
        request.removeHeader("X-Admin-Key");
        request.addHeader("X-Admin-Key", KEY);
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        request.addHeader("X-Admin-Key", KEY);
        rejected(filter, request);
        request.removeHeader("X-Admin-Key");
        request.addHeader("X-Admin-Key", "a".repeat(513));
        rejected(filter, request);
    }

    @Test
    void absentConfigurationDisablesAdminEvenWithSuppliedKey() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/admin");
        request.addHeader("X-Admin-Key", KEY);
        rejected(new AdminAuthenticationFilter(""), request);
    }

    @Test
    void decodedAndContextPathsCannotBypassAuthentication() throws Exception {
        var request = new MockHttpServletRequest("GET", "/app/api/v1/%61dmin/concerts");
        request.setContextPath("/app");
        request.setServletPath("/api/v1/admin/concerts");
        rejected(filter, request);
        request = new MockHttpServletRequest("GET", "/app/api/v1/admin/concerts");
        request.setContextPath("/app");
        rejected(filter, request);
    }

    @Test
    void publicRoutesDoNotRequireAdministrativeKey() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/concerts");
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void administrativeMutationsRequireBothOriginAndKey() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .addFilters(new BrowserOriginFilter("https://tickets.example.com"), filter).build();
        mvc.perform(post("/api/v1/admin/probe").header("X-Admin-Key", KEY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/probe").header("Origin", "https://tickets.example.com"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/probe").header("Origin", "https://tickets.example.com")
                        .header("X-Admin-Key", KEY)).andExpect(status().isOk());
        var request = new MockHttpServletRequest("POST", "/%61pi/v1/admin/probe");
        request.setServletPath("/api/v1/admin/probe");
        var response = new MockHttpServletResponse();
        new BrowserOriginFilter("https://tickets.example.com").doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "                                ", "invalid\r\nheader"})
    void rejectsInvalidConfiguredKeys(String key) {
        assertThatThrownBy(() -> new AdminAuthenticationFilter(key)).isInstanceOf(IllegalArgumentException.class);
    }

    private void rejected(AdminAuthenticationFilter target, MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        target.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo("{\"status\":401,\"code\":\"ADMIN_AUTHENTICATION_REQUIRED\"}");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(chain.getRequest()).isNull();
    }

    @RestController
    static class Probe {
        @PostMapping("/api/v1/admin/probe")
        void mutate() {
        }
    }
}
