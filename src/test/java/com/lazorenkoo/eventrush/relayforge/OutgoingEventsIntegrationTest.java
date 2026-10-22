package com.lazorenkoo.eventrush.relayforge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
class OutgoingEventsIntegrationTest {

    @Autowired
    private OutgoingEvents events;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void committedEventKeepsItsIdAndExactJsonPayload() {
        UUID reservation = UUID.randomUUID();
        UUID id = new TransactionTemplate(transactions).execute(status ->
                events.enqueue("ticket.reserved", Map.of("reservationId", reservation)));
        try {
            var saved = jdbc.queryForMap("select * from outgoing_events where id = ?", id);
            assertThat(saved.get("event_type")).isEqualTo("ticket.reserved");
            assertThat(saved.get("payload")).isEqualTo("{\"reservationId\":\"" + reservation + "\"}");
            assertThat(saved.get("published_at")).isNull();
            assertThat(saved.get("attempt_count")).isEqualTo(0);
        } finally {
            jdbc.update("delete from outgoing_events where id = ?", id);
        }
    }

    @Test
    void rollbackRemovesBothBusinessChangeAndOutgoingEvent() {
        UUID concert = UUID.randomUUID();
        UUID event = new TransactionTemplate(transactions).execute(status -> {
            jdbc.update("""
                    insert into concerts (id, name, starts_at, sale_starts_at, crowdgate_gate_id)
                    values (?, 'Rollback show', current_timestamp + interval '1 day', current_timestamp, ?)
                    """, concert, UUID.randomUUID());
            UUID id = events.enqueue("concert.sold_out", Map.of("concertId", concert));
            status.setRollbackOnly();
            return id;
        });
        assertThat(jdbc.queryForObject("select count(*) from concerts where id = ?",
                Integer.class, concert)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outgoing_events where id = ?",
                Integer.class, event)).isZero();
    }

    @Test
    void refusesToSaveAnEventOutsideABusinessTransaction() {
        assertThatThrownBy(() -> events.enqueue("ticket.reserved", Map.of("seatId", UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
