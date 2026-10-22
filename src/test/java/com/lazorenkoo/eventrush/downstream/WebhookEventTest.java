package com.lazorenkoo.eventrush.downstream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookEventTest {

    private static final String ID = "a0000000-0000-0000-0000-000000000071";
    private static final String PAYLOAD = "{\"amount\":12.50,\"message\":\"Zażółć\",\"_eventrush\":{\"id\":\""
            + ID + "\",\"type\":\"ticket.purchased\",\"version\":1}}";

    @Test
    void preservesIdentityBusinessDataAndExactSignedBytes() throws Exception {
        byte[] raw = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        WebhookEvent event = WebhookEvent.parse(raw);
        assertThat(event.id()).isEqualTo(UUID.fromString(ID));
        assertThat(event.type()).isEqualTo("ticket.purchased");
        assertThat(event.payload()).isEqualTo(PAYLOAD);
        assertThat(event.data().path("amount").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(event.data().path("message").asText()).isEqualTo("Zażółć");
        assertThat(event.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        assertThat(parse(PAYLOAD + " ").sha256()).isNotEqualTo(event.sha256());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ticket.reserved", "ticket.purchased", "reservation.expired",
            "payment.failed", "concert.sold_out"})
    void acceptsEveryProducedEventType(String type) {
        assertThat(parse(PAYLOAD.replace("ticket.purchased", type)).type()).isEqualTo(type);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "null", "[]", "1", "{}", "{\"_eventrush\":null}",
            "{\"reservationId\":\"legacy\"}", "invalid"})
    void rejectsAbsentLegacyAndNonobjectPayloads(String body) {
        invalid(body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2", "1.0", "\"1\"", "null", "true", "4294967297"})
    void versionMustBeTheSupportedInteger(String version) {
        invalid(PAYLOAD.replace("\"version\":1", "\"version\":" + version).getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1-1-1-1-1", "A0000000-0000-0000-0000-000000000071", "invalid", ""})
    void rejectsNoncanonicalIdentifiers(String id) {
        invalid(PAYLOAD.replace(ID, id).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsAmbiguousJsonAndUnknownTypes() {
        invalid((PAYLOAD + " {}").getBytes(StandardCharsets.UTF_8));
        invalid(PAYLOAD.replace("12.50", "12.50,\"amount\":1").getBytes(StandardCharsets.UTF_8));
        invalid(PAYLOAD.replace("\"version\":1", "\"version\":1,\"version\":1").getBytes(StandardCharsets.UTF_8));
        invalid(PAYLOAD.replace("ticket.purchased", "unknown").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void invalidUtf8AndUtf16AreNotSilentlyDecoded() {
        invalid(new byte[] {(byte) 0xc3, (byte) 0x28});
        invalid(PAYLOAD.getBytes(StandardCharsets.UTF_16));
    }

    @Test
    void bodyLimitCountsBytesRatherThanCharacters() {
        byte[] raw = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        String boundary = PAYLOAD + " ".repeat(65_536 - raw.length);
        assertThat(parse(boundary).id()).isEqualTo(UUID.fromString(ID));
        invalid((boundary + " ").getBytes(StandardCharsets.UTF_8));
    }

    private WebhookEvent parse(String body) {
        return WebhookEvent.parse(body.getBytes(StandardCharsets.UTF_8));
    }

    private void invalid(byte[] body) {
        assertThatThrownBy(() -> WebhookEvent.parse(body))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(400);
                    assertThat(exception.getReason()).isEqualTo("INVALID_WEBHOOK_EVENT");
                });
    }
}
