package com.lazorenkoo.eventrush.admission;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserProtectionTest {

    private static final String ORIGIN = "https://tickets.example.com";
    private final BrowserOriginFilter filter = new BrowserOriginFilter(ORIGIN);
    private final SessionCookie cookies = new SessionCookie(true);

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void unsafeApiRequestsRequireOneExactOrigin(String method) throws Exception {
        var request = new MockHttpServletRequest(method, "/api/v1/admission");
        assertRejected(request);
        request.addHeader("Origin", ORIGIN + ".attacker.example");
        assertRejected(request);
        request.removeHeader("Origin");
        request.addHeader("Origin", ORIGIN);
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
        request.addHeader("Origin", ORIGIN);
        assertRejected(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void safeMethodsDoNotRequireOrigin(String method) throws Exception {
        var request = new MockHttpServletRequest(method, "/api/v1/concerts");
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void contextPathCannotBypassOriginCheckAndWebhooksRemainSeparate() throws Exception {
        var request = new MockHttpServletRequest("POST", "/eventrush/api/v1/admission");
        request.setContextPath("/eventrush");
        assertRejected(request);
        request = new MockHttpServletRequest("POST", "/webhooks/tickets");
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void cookieHasSecureHostOnlyFlagsAndClearsWithSameScope() {
        String header = cookies.issue("a".repeat(43));
        assertThat(header).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/", "Max-Age=86400")
                .doesNotContain("Domain=");
        assertThat(cookies.clear()).contains("Max-Age=0", "Path=/", "HttpOnly", "Secure", "SameSite=Strict");
        assertThat(new SessionCookie(false).issue("a".repeat(43))).doesNotContain("Secure");
    }

    @Test
    void cookieExtractionRejectsMissingMalformedAndDuplicateTokens() {
        var request = new MockHttpServletRequest();
        unauthorized(request);
        request.setCookies(new Cookie(SessionCookie.NAME, "bad-token"));
        unauthorized(request);
        String token = "a".repeat(43);
        request.setCookies(new Cookie("other", "value"), new Cookie(SessionCookie.NAME, token));
        assertThat(cookies.require(request)).isEqualTo(token);
        request.setCookies(new Cookie(SessionCookie.NAME, token), new Cookie(SessionCookie.NAME, token));
        unauthorized(request);
        assertThatThrownBy(() -> cookies.issue("invalid")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "https://tickets.example.com/", "https://user@tickets.example.com", "file:///tmp"})
    void rejectsNonOriginConfiguration(String origin) {
        assertThatThrownBy(() -> new BrowserOriginFilter(origin)).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertRejected(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    private void unauthorized(MockHttpServletRequest request) {
        assertThatThrownBy(() -> cookies.require(request)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
    }
}
