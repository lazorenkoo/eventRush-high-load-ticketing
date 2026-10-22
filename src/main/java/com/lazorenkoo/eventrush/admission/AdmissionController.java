package com.lazorenkoo.eventrush.admission;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admission")
@ConditionalOnProperty(name = "eventrush.crowdgate.enabled", havingValue = "true")
public class AdmissionController {

    private final AdmissionService service;
    private final AdmissionSessionStore sessions;
    private final SessionCookie cookies;

    public AdmissionController(AdmissionService service, AdmissionSessionStore sessions, SessionCookie cookies) {
        this.service = service;
        this.sessions = sessions;
        this.cookies = cookies;
    }

    @PostMapping("/exchange")
    public ResponseEntity<Void> exchange(@Valid @RequestBody Exchange request, HttpServletRequest http) {
        String token = service.exchange(request.concertId(), visitor(http), request.admissionToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.issue(token)).build();
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<SessionExpiry> heartbeat(HttpServletRequest http) {
        Instant expiresAt = service.heartbeat(cookies.require(http));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionExpiry(expiresAt));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        sessions.revoke(cookies.require(http));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }

    private UUID visitor(HttpServletRequest http) {
        var all = http.getCookies();
        var visitors = all == null ? java.util.List.<jakarta.servlet.http.Cookie>of()
                : Arrays.stream(all).filter(cookie -> "cg_user_id".equals(cookie.getName())).toList();
        if (visitors.size() == 1) {
            try {
                String value = visitors.getFirst().getValue();
                UUID id = UUID.fromString(value);
                if (id.toString().equals(value)) {
                    return id;
                }
            } catch (IllegalArgumentException error) {
                // Invalid visitor identity cannot be used to redeem an admission.
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "CROWDGATE_VISITOR_REQUIRED");
    }

    public record Exchange(@NotNull UUID concertId, @NotBlank @Size(max = 8192) String admissionToken) {
        @Override
        public String toString() {
            return "Exchange[concertId=" + concertId + "]";
        }
    }

    public record SessionExpiry(Instant expiresAt) {
    }
}
