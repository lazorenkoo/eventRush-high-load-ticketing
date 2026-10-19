package com.lazorenkoo.eventrush.admission;

import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient.Admission;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AdmissionSessionStore {

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public AdmissionSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String create(Admission admission, Instant admissionExpiresAt) {
        if (admissionExpiresAt == null || !admissionExpiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ADMISSION_EXPIRED");
        }
        if (admission.sessionCredential() == null
                || !admission.sessionCredential().matches(admission.gateId() + ":[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid admission credential");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("""
                insert into admission_sessions
                    (token_hash, owner_id, gate_id, queue_session_id, session_credential, admission_expires_at)
                values (?, ?, ?, ?, ?, ?)
                """, hash(token), admission.visitorId(), admission.gateId(), admission.queueSessionId(),
                admission.sessionCredential(), Timestamp.from(admissionExpiresAt));
        return token;
    }

    public Session require(String token) {
        String digest = hash(token);
        var sessions = jdbc.query("""
                select owner_id, gate_id, queue_session_id, session_credential, admission_expires_at, expires_at
                from admission_sessions where token_hash = ? and revoked_at is null and expires_at > clock_timestamp()
                """, (row, index) -> new Session(new Admission(row.getObject("gate_id", UUID.class),
                row.getObject("owner_id", UUID.class), row.getObject("queue_session_id", UUID.class),
                row.getString("session_credential")), row.getTimestamp("admission_expires_at").toInstant(),
                row.getTimestamp("expires_at").toInstant()), digest);
        if (sessions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED");
        }
        return sessions.getFirst();
    }

    public void revoke(String token) {
        jdbc.update("update admission_sessions set revoked_at = clock_timestamp() where token_hash = ?", hash(token));
    }

    private String hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public record Session(Admission admission, Instant admissionExpiresAt, Instant expiresAt) {
    }
}
