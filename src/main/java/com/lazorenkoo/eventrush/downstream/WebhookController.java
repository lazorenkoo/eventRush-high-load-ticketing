package com.lazorenkoo.eventrush.downstream;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "eventrush.downstream.enabled", havingValue = "true")
public class WebhookController {

    private static final int MAX_BODY_BYTES = 65_536;
    private final DownstreamProcessor processor;
    private final Map<DownstreamProcessor.Consumer, String> secrets;

    public WebhookController(DownstreamProcessor processor,
            @Value("${eventrush.downstream.tickets-secret}") String ticketsSecret,
            @Value("${eventrush.downstream.accounting-secret}") String accountingSecret,
            @Value("${eventrush.downstream.notifications-secret}") String notificationsSecret) {
        this.processor = processor;
        this.secrets = Map.of(DownstreamProcessor.Consumer.TICKETS, validated(ticketsSecret),
                DownstreamProcessor.Consumer.ACCOUNTING, validated(accountingSecret),
                DownstreamProcessor.Consumer.NOTIFICATIONS, validated(notificationsSecret));
        if (secrets.values().stream().distinct().count() != 3) {
            throw new IllegalArgumentException("Each webhook receiver requires its own signing secret");
        }
    }

    @PostMapping(value = "/webhooks/{recipient}", consumes = "application/json")
    public ResponseEntity<Void> receive(@PathVariable String recipient, HttpServletRequest request) throws IOException {
        var consumer = switch (recipient) {
            case "tickets" -> DownstreamProcessor.Consumer.TICKETS;
            case "accounting" -> DownstreamProcessor.Consumer.ACCOUNTING;
            case "notifications" -> DownstreamProcessor.Consumer.NOTIFICATIONS;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "WEBHOOK_RECEIVER_NOT_FOUND");
        };
        String timestamp = singleHeader(request, "X-Webhook-Timestamp");
        String signature = singleHeader(request, "X-Webhook-Signature");
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw tooLarge();
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw tooLarge();
        }
        WebhookSignatureVerifier.verify(secrets.get(consumer), timestamp, signature, body, Instant.now());
        processor.process(consumer, WebhookEvent.parse(body));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private String singleHeader(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE");
        }
        return values.getFirst();
    }

    private static String validated(String secret) {
        if (secret.length() < 32 || secret.length() > 512 || secret.contains("\r") || secret.contains("\n")) {
            throw new IllegalArgumentException("Webhook signing secret must contain 32 to 512 characters");
        }
        return secret;
    }

    private ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "WEBHOOK_BODY_TOO_LARGE");
    }
}
