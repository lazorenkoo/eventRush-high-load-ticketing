package com.lazorenkoo.eventrush.downstream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayForgeEnvelopeTest {

    private static final String SOURCE_ID = "00000000-0000-0000-0000-000000000071";
    private static final String DATA = "{\"amount\":25.00,\"_eventrush\":{\"id\":\"" + SOURCE_ID
            + "\",\"type\":\"ticket.purchased\",\"version\":1}}";

    @Test
    void unwrapsRealRelayForgeShapeAndUsesSignedSourceUuid() throws Exception {
        String body = envelope("ticket.purchased", DATA);
        WebhookEvent event = parse(body);
        assertThat(event.id()).isEqualTo(UUID.fromString(SOURCE_ID));
        assertThat(event.type()).isEqualTo("ticket.purchased");
        assertThat(event.data().path("amount").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(event.data().has("createdAt")).isFalse();
        assertThat(event.payload()).isEqualTo(body);
        assertThat(event.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8))));
        assertThat(event.sha256()).isNotEqualTo(parse(DATA).sha256());
    }

    @Test
    void changingRelayForgeNumericIdDoesNotReplaceSourceIdentityButChangesDigest() {
        String original = envelope("ticket.purchased", DATA);
        WebhookEvent changed = parse(original.replace("\"id\":8123", "\"id\":9000"));
        assertThat(changed.id()).isEqualTo(parse(original).id());
        assertThat(changed.sha256()).isNotEqualTo(parse(original).sha256());
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment.failed", "unknown", ""})
    void envelopeTypeMustAgreeWithSourceMetadata(String type) {
        invalid(envelope(type, DATA));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "25", "\"text\"", "{}"})
    void envelopeDataMustBeAnIdentifiedObject(String data) {
        invalid(envelope("ticket.purchased", data));
    }

    @Test
    void duplicateEnvelopeFieldsAndMissingTypeAreRejected() {
        String body = envelope("ticket.purchased", DATA);
        invalid(body.replace("\"id\":8123", "\"id\":8123,\"id\":9000"));
        invalid(body.replace("\"data\":", "\"data\":{},\"data\":"));
        invalid(body.replace("\"type\":\"ticket.purchased\",\"createdAt\"", "\"createdAt\""));
    }

    private String envelope(String type, String data) {
        return "{\"id\":8123,\"type\":\"" + type + "\",\"createdAt\":\"2026-10-20T16:00:00Z\",\"data\":" + data + "}";
    }

    private WebhookEvent parse(String body) {
        return WebhookEvent.parse(body.getBytes(StandardCharsets.UTF_8));
    }

    private void invalid(String body) {
        assertThatThrownBy(() -> parse(body)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
    }
}
