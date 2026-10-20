package com.lazorenkoo.eventrush.relayforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_identity_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "eventrush.events.include-identity=true"
})
@Transactional
class OutgoingIdentityIntegrationTest {

    @Autowired
    private OutgoingEvents events;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void identityMatchesThePersistedRowAndPreservesBusinessFields() throws Exception {
        UUID reservation = UUID.randomUUID();
        UUID id = events.enqueue("ticket.purchased", Map.of("reservationId", reservation,
                "amount", new BigDecimal("12.50"), "message", "Zażółć"));
        var row = jdbc.queryForMap("select event_type, payload from outgoing_events where id = ?", id);
        var body = mapper.readTree((String) row.get("payload"));
        assertThat(body.path("_eventrush").path("id").asText()).isEqualTo(id.toString());
        assertThat(body.path("_eventrush").path("type").asText()).isEqualTo(row.get("event_type"));
        assertThat(body.path("_eventrush").path("version").asInt()).isEqualTo(1);
        assertThat(body.path("reservationId").asText()).isEqualTo(reservation.toString());
        assertThat(body.path("amount").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(body.path("message").asText()).isEqualTo("Zażółć");
    }

    @Test
    void callerMapIsNotModifiedAndLaterChangesCannotAlterPersistedBytes() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("amount", 25);
        UUID id = events.enqueue("ticket.purchased", payload);
        String saved = payload(id);
        assertThat(payload).containsOnlyKeys("amount");
        payload.put("amount", 100);
        payload.put("_eventrush", "spoof");
        assertThat(payload(id)).isEqualTo(saved);
    }

    @Test
    void distinctEventsDoNotShareIdentityEvenForIdenticalBusinessData() throws Exception {
        UUID first = events.enqueue("ticket.reserved", Map.of("message", "same"));
        UUID second = events.enqueue("ticket.reserved", Map.of("message", "same"));
        assertThat(first).isNotEqualTo(second);
        assertThat(mapper.readTree(payload(first)).path("_eventrush").path("id").asText())
                .isNotEqualTo(mapper.readTree(payload(second)).path("_eventrush").path("id").asText());
    }

    @Test
    void callerCannotSupplyTheSignedIdentity() {
        assertThatThrownBy(() -> events.enqueue("ticket.purchased", Map.of("_eventrush",
                Map.of("id", UUID.randomUUID(), "type", "concert.sold_out", "version", 1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event payload contains reserved metadata");
        assertThat(jdbc.queryForObject("select count(*) from outgoing_events", Integer.class)).isZero();
    }

    private String payload(UUID id) {
        return jdbc.queryForObject("select payload from outgoing_events where id = ?", String.class, id);
    }
}
