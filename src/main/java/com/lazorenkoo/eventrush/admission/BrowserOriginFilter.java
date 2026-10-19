package com.lazorenkoo.eventrush.admission;

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
import java.net.URI;
import java.util.Collections;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BrowserOriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final String allowedOrigin;

    public BrowserOriginFilter(@Value("${eventrush.browser.origin:http://localhost:8082}") String allowedOrigin) {
        URI origin = URI.create(allowedOrigin);
        if (!("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null || !origin.getPath().isEmpty()) {
            throw new IllegalArgumentException("Browser origin must be an HTTP origin without trailing slash");
        }
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/") && !SAFE_METHODS.contains(request.getMethod())) {
            var origins = Collections.list(request.getHeaders("Origin"));
            if (origins.size() != 1 || !allowedOrigin.equals(origins.getFirst())) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"status\":403,\"code\":\"ORIGIN_REJECTED\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
