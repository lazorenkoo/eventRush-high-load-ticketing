package com.lazorenkoo.eventrush.downstream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "signature-test-key-at-least-32-characters";
    private static final String TIMESTAMP = "1792497600";
    private static final Instant NOW = Instant.ofEpochSecond(1792497600L);
    private static final byte[] BODY = "{\"message\":\"Zażółć\"}".getBytes(StandardCharsets.UTF_8);
    // Independent .NET HMACSHA256 vector over timestamp + '.' + the UTF-8 body.
    private static final String SIGNATURE =
            "sha256=b7f59b4acb1e7d8adaf9fac8ae8a9c7b252f47b78a43ecc1f468098e90c40146";

    @ParameterizedTest
    @ValueSource(longs = {-300, -1, 0, 1, 300})
    void acceptsValidSignatureWithinInclusiveClockWindow(long offset) {
        assertThatCode(() -> WebhookSignatureVerifier.verify(SECRET, TIMESTAMP, SIGNATURE, BODY,
                NOW.plusSeconds(offset))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {-301, 301, -3600, 3600})
    void rejectsStaleOrFutureDeliveries(long offset) {
        unauthorized(SECRET, TIMESTAMP, SIGNATURE, BODY, NOW.plusSeconds(offset));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"-1", "0", "01792497600", "1792497600.0", " 1792497600",
            "1792497600 ", "99999999999999999999999", "NaN"})
    void rejectsMalformedTimestamps(String timestamp) {
        unauthorized(SECRET, timestamp, SIGNATURE, BODY, NOW);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"sha256=00", "sha512=00", "SHA256=00", "sha256=not-hex"})
    void rejectsMalformedSignatures(String signature) {
        unauthorized(SECRET, TIMESTAMP, signature, BODY, NOW);
    }

    @Test
    void rejectsCorrectlySizedButIncorrectSignaturesAndWrongKey() {
        unauthorized(SECRET, TIMESTAMP, "sha256=" + "0".repeat(64), BODY, NOW);
        unauthorized("another-test-key-at-least-32-characters", TIMESTAMP, SIGNATURE, BODY, NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{\"message\":\"other\"}", "{ \"message\":\"Zażółć\"}",
            "{\"message\":\"Zażółć\"}\n"})
    void anyBodyChangeInvalidatesTheSignature(String body) {
        unauthorized(SECRET, TIMESTAMP, SIGNATURE, body.getBytes(StandardCharsets.UTF_8), NOW);
    }

    @Test
    void timestampIsAlsoAuthenticatedAndBodyCannotBeAbsent() {
        unauthorized(SECRET, "1792497601", SIGNATURE, BODY, NOW);
        unauthorized(SECRET, TIMESTAMP, SIGNATURE, null, NOW);
    }

    @Test
    void uppercaseDigestIsNotTheRelayForgeWireFormat() {
        unauthorized(SECRET, TIMESTAMP, "sha256=" + SIGNATURE.substring(7).toUpperCase(), BODY, NOW);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short"})
    void invalidConfigurationFailsClosed(String secret) {
        assertThatThrownBy(() -> WebhookSignatureVerifier.verify(secret, TIMESTAMP, SIGNATURE, BODY, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedSigningSecretIsRejected() {
        assertThatThrownBy(() -> WebhookSignatureVerifier.verify("a".repeat(513), TIMESTAMP, SIGNATURE, BODY, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void unauthorized(String secret, String timestamp, String signature, byte[] body, Instant now) {
        assertThatThrownBy(() -> WebhookSignatureVerifier.verify(secret, timestamp, signature, body, now))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(401);
                    assertThat(exception.getReason()).isEqualTo("INVALID_WEBHOOK_SIGNATURE");
                });
    }
}
