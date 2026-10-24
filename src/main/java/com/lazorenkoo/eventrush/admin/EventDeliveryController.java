package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.relayforge.RelayForgeClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/concerts/{concertId}/events/{eventId}")
@ConditionalOnProperty(name = "eventrush.relayforge.enabled", havingValue = "true")
public class EventDeliveryController {
    private final JdbcTemplate jdbc;
    private final RelayForgeClient relay;

    public EventDeliveryController(JdbcTemplate jdbc, RelayForgeClient relay) {
        this.jdbc = jdbc;
        this.relay = relay;
    }

    @GetMapping("/deliveries")
    public ResponseEntity<History> deliveries(@PathVariable UUID concertId, @PathVariable UUID eventId) {
        var event = jdbc.query("""
                select event_type,payload from outgoing_events
                where id=? and published_at is not null and payload::jsonb->>'concertId'=?
                """, (row, index) -> new PublishedEvent(row.getString("event_type"),
                row.getString("payload")), eventId, concertId.toString())
                .stream().findFirst().orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "PUBLISHED_EVENT_NOT_FOUND"));
        try {
            // Reuse the original key and exact stored payload; RelayForge owns event deduplication.
            long remoteId = relay.publishedId(eventId, event.type(), event.payload());
            var history = new History(eventId, remoteId, relay.deliveries(remoteId));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history);
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "RELAYFORGE_UNAVAILABLE");
        }
    }

    @GetMapping("/deliveries/{deliveryId}/attempts")
    public ResponseEntity<List<RelayForgeClient.AttemptView>> attempts(@PathVariable UUID concertId,
            @PathVariable UUID eventId, @PathVariable long deliveryId) {
        if (deliveryId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DELIVERY_ID");
        }
        History history = deliveries(concertId, eventId).getBody();
        if (history == null || history.deliveries().stream().noneMatch(row -> row.id() == deliveryId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_DELIVERY_NOT_FOUND");
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(relay.attempts(deliveryId));
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "RELAYFORGE_UNAVAILABLE");
        }
    }
    @PostMapping("/deliveries/{deliveryId}/retry")
    public ResponseEntity<RelayForgeClient.RetryResult> retry(@PathVariable UUID concertId,
            @PathVariable UUID eventId, @PathVariable long deliveryId) {
        if (deliveryId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DELIVERY_ID");
        }
        History history = deliveries(concertId, eventId).getBody();
        var delivery = history == null ? null : history.deliveries().stream()
                .filter(row -> row.id() == deliveryId).findFirst().orElse(null);
        if (delivery == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_DELIVERY_NOT_FOUND");
        }
        if (!"FAILED".equals(delivery.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DELIVERY_NOT_RETRYABLE");
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(relay.retry(deliveryId));
        } catch (RelayForgeClient.RemoteConflict error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DELIVERY_NOT_RETRYABLE");
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "RELAYFORGE_UNAVAILABLE");
        }
    }
    private record PublishedEvent(String type, String payload) {}

    public record History(UUID eventId, long relayEventId, List<RelayForgeClient.DeliveryView> deliveries) {}
}
