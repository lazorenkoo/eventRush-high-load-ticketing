package com.lazorenkoo.eventrush.downstream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

public record WebhookEvent(UUID id, String type, JsonNode data, String payload, String sha256) {

    private static final Set<String> TYPES = Set.of("ticket.reserved", "ticket.purchased",
            "reservation.expired", "payment.failed", "concert.sold_out");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public static WebhookEvent parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > 65_536) {
            throw invalid();
        }
        try {
            String payload = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
            JsonNode data = MAPPER.readTree(payload);
            if (data == null || !data.isObject()) {
                throw invalid();
            }
            JsonNode envelopeType = null;
            if (data.has("data")) {
                envelopeType = data.path("type");
                data = data.path("data");
                if (!envelopeType.isTextual() || !data.isObject()) {
                    throw invalid();
                }
            }
            JsonNode metadata = data.path("_eventrush");
            JsonNode version = metadata.path("version");
            JsonNode type = metadata.path("type");
            JsonNode identifier = metadata.path("id");
            if (!metadata.isObject() || !version.isIntegralNumber() || !version.canConvertToInt()
                    || version.intValue() != 1 || !type.isTextual() || !TYPES.contains(type.textValue())
                    || !identifier.isTextual() || envelopeType != null && !envelopeType.equals(type)) {
                throw invalid();
            }
            UUID id = UUID.fromString(identifier.textValue());
            if (!id.toString().equals(identifier.textValue())) {
                throw invalid();
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            return new WebhookEvent(id, type.textValue(), data, payload, hash);
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK_EVENT");
    }
}
