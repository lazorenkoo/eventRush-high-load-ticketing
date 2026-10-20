package com.lazorenkoo.eventrush.downstream;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

public final class WebhookSignatureVerifier {

    private static final long MAX_CLOCK_SKEW_SECONDS = 300;

    private WebhookSignatureVerifier() {
    }

    public static void verify(String secret, String timestamp, String signature, byte[] body, Instant now) {
        if (secret == null || secret.length() < 32 || secret.length() > 512) {
            throw new IllegalArgumentException("Webhook signing secret must contain 32 to 512 characters");
        }
        if (timestamp == null || !timestamp.matches("[1-9][0-9]{0,11}")
                || signature == null || !signature.matches("sha256=[0-9a-f]{64}") || body == null) {
            throw unauthorized();
        }
        long sentAt = Long.parseLong(timestamp);
        long current = now.getEpochSecond();
        if (sentAt < current - MAX_CLOCK_SKEW_SECONDS || sentAt > current + MAX_CLOCK_SKEW_SECONDS) {
            throw unauthorized();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            byte[] expected = mac.doFinal(body);
            byte[] provided = HexFormat.of().parseHex(signature.substring(7));
            if (!MessageDigest.isEqual(expected, provided)) {
                throw unauthorized();
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not verify webhook signature", exception);
        }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE");
    }
}
