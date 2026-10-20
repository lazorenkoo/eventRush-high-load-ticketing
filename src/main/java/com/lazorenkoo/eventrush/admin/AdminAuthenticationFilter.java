package com.lazorenkoo.eventrush.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class AdminAuthenticationFilter extends OncePerRequestFilter {

    private final byte[] expectedDigest;

    public AdminAuthenticationFilter(@Value("${eventrush.admin.api-key:}") String apiKey) {
        if (!apiKey.isEmpty() && (apiKey.isBlank() || apiKey.length() < 32 || apiKey.length() > 512
                || apiKey.contains("\r") || apiKey.contains("\n"))) {
            throw new IllegalArgumentException("Admin API key must contain 32 to 512 characters");
        }
        expectedDigest = apiKey.isEmpty() ? null : digest(apiKey);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        if (path.equals("/api/v1/admin") || path.startsWith("/api/v1/admin/")) {
            var keys = Collections.list(request.getHeaders("X-Admin-Key"));
            boolean valid = expectedDigest != null && keys.size() == 1 && keys.getFirst().length() <= 512
                    && MessageDigest.isEqual(expectedDigest, digest(keys.getFirst()));
            if (!valid) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"status\":401,\"code\":\"ADMIN_AUTHENTICATION_REQUIRED\"}");
                return;
            }
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }

    private static byte[] digest(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
