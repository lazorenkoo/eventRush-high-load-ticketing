package com.lazorenkoo.eventrush.relayforge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
@ConditionalOnProperty(name = "eventrush.relayforge.enabled", havingValue = "true")
public class OutgoingEventPublisher {

    private final JdbcTemplate jdbc;
    private final RelayForgeClient client;
    private final TransactionTemplate transaction;

    public OutgoingEventPublisher(JdbcTemplate jdbc, RelayForgeClient client, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.client = client;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(15);
    }

    @Transactional(propagation = Propagation.NEVER)
    public boolean publishNext() {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var events = jdbc.query("""
                    select id, event_type, payload from outgoing_events
                    where published_at is null and next_attempt_at <= clock_timestamp()
                    order by next_attempt_at, created_at, id limit 1 for update skip locked
                    """, (row, index) -> new Event(row.getObject("id", UUID.class),
                    row.getString("event_type"), row.getString("payload")));
            if (events.isEmpty()) {
                return false;
            }
            Event event = events.getFirst();
            try {
                client.publish(event.id(), event.type(), event.payload());
            } catch (RuntimeException error) {
                jdbc.update("""
                        update outgoing_events set attempt_count = attempt_count + 1,
                            next_attempt_at = clock_timestamp()
                                + least(300, power(2, least(attempt_count + 1, 9))) * interval '1 second',
                            last_error = ? where id = ?
                        """, error.getClass().getSimpleName(), event.id());
                return true;
            }
            jdbc.update("""
                    update outgoing_events set published_at = clock_timestamp(),
                        attempt_count = attempt_count + 1, last_error = null where id = ?
                    """, event.id());
            return true;
        }));
    }

    private record Event(UUID id, String type, String payload) {
    }
}
