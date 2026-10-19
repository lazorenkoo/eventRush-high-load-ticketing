package com.lazorenkoo.eventrush.admission;

import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient.Admission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
@Transactional
class AdmissionSessionStoreIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdmissionSessionStore store;

    private final UUID gate = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final Admission admission = new Admission(gate, owner, UUID.randomUUID(), gate + ":" + "a".repeat(43));

    @BeforeEach
    void createConcert() {
        jdbc.update("""
                insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id)
                values ('Session show', now() + interval '1 day', now(), ?)
                """, gate);
    }

    @Test
    void storesOnlyHashAndPreservesTrustedOwnerAndAdmission() throws Exception {
        Instant expiry = Instant.now().plusSeconds(60);
        String token = store.create(admission, expiry);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(jdbc.queryForObject("select token_hash from admission_sessions where owner_id = ?",
                String.class, owner)).isEqualTo(digest).isNotEqualTo(token);
        var session = store.require(token);
        assertThat(session.admission()).isEqualTo(admission);
        assertThat(session.admissionExpiresAt().toEpochMilli()).isEqualTo(expiry.toEpochMilli());
        assertThat(session.expiresAt()).isAfter(session.admissionExpiresAt());
        assertThat(session.toString()).doesNotContain(admission.sessionCredential());
    }

    @Test
    void independentTokensCanBeRevokedWithoutAffectingOtherSessions() {
        String first = store.create(admission, Instant.now().plusSeconds(60));
        String second = store.create(admission, Instant.now().plusSeconds(60));
        assertThat(first).isNotEqualTo(second);
        store.revoke(first);
        store.revoke(first);
        unauthorized(first);
        assertThat(store.require(second).admission().visitorId()).isEqualTo(owner);
    }

    @Test
    void expiredApplicationSessionIsRejectedIndependentlyOfAdmissionExpiry() {
        String token = store.create(admission, Instant.now().plusSeconds(60));
        jdbc.update("update admission_sessions set admission_expires_at = now() - interval '1 second' where owner_id = ?",
                owner);
        assertThat(store.require(token).admission().visitorId()).isEqualTo(owner);
        jdbc.update("""
                update admission_sessions set created_at = now() - interval '2 days',
                    expires_at = now() - interval '1 day' where owner_id = ?
                """, owner);
        unauthorized(token);
    }

    @Test
    void rejectsUnknownMalformedAndMissingTokens() {
        unauthorized("b".repeat(43));
        unauthorized("bad\r\ntoken");
        unauthorized(null);
    }

    @Test
    void cannotCreateSessionFromExpiredAdmissionOrInvalidCredential() {
        assertThatThrownBy(() -> store.create(admission, Instant.EPOCH))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        var invalid = new Admission(gate, owner, UUID.randomUUID(), "invalid");
        assertThatThrownBy(() -> store.create(invalid, Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from admission_sessions where owner_id = ?",
                Integer.class, owner)).isZero();
    }

    private void unauthorized(String token) {
        assertThatThrownBy(() -> store.require(token)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
    }
}
