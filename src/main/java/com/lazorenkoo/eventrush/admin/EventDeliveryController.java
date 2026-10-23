package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.relayforge.RelayForgeClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    private record PublishedEvent(String type, String payload) {}

    public record History(UUID eventId, long relayEventId, List<RelayForgeClient.DeliveryView> deliveries) {}
}
