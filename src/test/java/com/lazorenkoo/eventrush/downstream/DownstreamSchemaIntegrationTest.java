package com.lazorenkoo.eventrush.downstream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
@Transactional
class DownstreamSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void eachConsumerCanProcessTheSameEventIndependently() {
        UUID event = UUID.randomUUID();
        receipt("TICKETS", event, "a".repeat(64));
        receipt("ACCOUNTING", event, "b".repeat(64));
        receipt("NOTIFICATIONS", event, "c".repeat(64));
        assertThat(jdbc.queryForObject("select count(*) from webhook_receipts where event_id = ?",
                Integer.class, event)).isEqualTo(3);
    }

    @Test
    void repeatedReceiptCannotBeRecordedAgainWithDifferentContent() {
        UUID event = UUID.randomUUID();
        receipt("TICKETS", event, "a".repeat(64));
        assertThatThrownBy(() -> receipt("TICKETS", event, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ABC", "z", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void receiptRequiresAFullLowercaseHexDigest(String hash) {
        assertThatThrownBy(() -> receipt("TICKETS", UUID.randomUUID(), hash))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ticketsReceiveDistinctPositiveNumbers() {
        long first = ticket(UUID.randomUUID(), UUID.randomUUID());
        long second = ticket(UUID.randomUUID(), UUID.randomUUID());
        assertThat(first).isPositive();
        assertThat(second).isGreaterThan(first);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void duplicateEventOrReservationCannotIssueAnotherTicket(boolean sameEvent) {
        UUID event = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        ticket(event, reservation);
        assertThatThrownBy(() -> ticket(sameEvent ? event : UUID.randomUUID(),
                sameEvent ? UUID.randomUUID() : reservation)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"event_id", "payment_id", "reservation_id"})
    void aPurchaseCannotBeCountedTwice(String column) {
        UUID identity = UUID.randomUUID();
        jdbc.update("insert into sales_entries(event_id, payment_id, reservation_id, concert_id, amount) "
                + "values (?, ?, ?, ?, 25)", identity, identity, identity, UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("insert into sales_entries(event_id, payment_id, reservation_id, "
                + "concert_id, amount) values (?, ?, ?, ?, 25)",
                column.equals("event_id") ? identity : UUID.randomUUID(),
                column.equals("payment_id") ? identity : UUID.randomUUID(),
                column.equals("reservation_id") ? identity : UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void revenueMustBePositive(int amount) {
        assertThatThrownBy(() -> jdbc.update("insert into sales_entries(event_id, payment_id, reservation_id, "
                + "concert_id, amount) values (?, ?, ?, ?, ?)", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), amount)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "invalid"})
    void notificationsRequireJsonObjects(String payload) {
        assertThatThrownBy(() -> jdbc.update("insert into simulated_notifications(event_id, event_type, payload) "
                + "values (?, 'ticket.purchased', ?)", UUID.randomUUID(), payload))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void receipt(String consumer, UUID event, String hash) {
        jdbc.update("insert into webhook_receipts(consumer, event_id, event_type, payload_sha256) "
                + "values (?, ?, 'ticket.purchased', ?)", consumer, event, hash);
    }

    private long ticket(UUID event, UUID reservation) {
        return jdbc.queryForObject("insert into ticket_records(event_id, reservation_id, concert_id, seat_id, user_id) "
                + "values (?, ?, ?, ?, ?) returning ticket_number", Long.class,
                event, reservation, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
