package com.lazorenkoo.eventrush.admission;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Arrays;

@Component
public class SessionCookie {

    public static final String NAME = "eventrush_session";
    private final boolean secure;

    public SessionCookie(@Value("${eventrush.browser.secure-cookie:true}") boolean secure) {
        this.secure = secure;
    }

    public String require(HttpServletRequest request) {
        var cookies = request.getCookies();
        var sessions = cookies == null ? java.util.List.<jakarta.servlet.http.Cookie>of()
                : Arrays.stream(cookies).filter(cookie -> NAME.equals(cookie.getName())).toList();
        if (sessions.size() != 1 || !sessions.getFirst().getValue().matches("[A-Za-z0-9_-]{43}")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED");
        }
        return sessions.getFirst().getValue();
    }

    public String issue(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid session token");
        }
        return cookie(token, Duration.ofDays(1)).toString();
    }

    public String clear() {
        return cookie("", Duration.ZERO).toString();
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/").maxAge(maxAge).build();
    }
}
