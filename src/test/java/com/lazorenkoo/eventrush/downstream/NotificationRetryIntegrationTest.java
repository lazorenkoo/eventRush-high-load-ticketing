package com.lazorenkoo.eventrush.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///notification_retry_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "eventrush.downstream.demo.fail-first-notification=true"
})
class NotificationRetryIntegrationTest {

    @Autowired
    private DownstreamProcessor processor;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void failureSurvivesOuterRollbackAndRetryProcessesExactlyOnce() throws Exception {
        UUID id = UUID.randomUUID();
        WebhookEvent event = event(id, "ticket.purchased");
        var outer = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> outer.execute(status -> {
            processor.process(DownstreamProcessor.Consumer.NOTIFICATIONS, event);
            return null;
        })).isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
            assertThat(exception.getStatusCode().value()).isEqualTo(503);
            assertThat(exception.getReason()).isEqualTo("DEMO_NOTIFICATION_UNAVAILABLE");
        });
        assertThat(count("notification_demo_failures", id)).isEqualTo(1);
        assertThat(count("webhook_receipts", id)).isZero();
        assertThat(count("simulated_notifications", id)).isZero();
        processor.process(DownstreamProcessor.Consumer.NOTIFICATIONS, event);
        processor.process(DownstreamProcessor.Consumer.NOTIFICATIONS, event);
        assertThat(count("notification_demo_failures", id)).isEqualTo(1);
        assertThat(count("webhook_receipts", id)).isEqualTo(1);
        assertThat(count("simulated_notifications", id)).isEqualTo(1);
    }

    @Test
    void ticketIssuanceAndAccountingAreUnaffectedByDemoMode() throws Exception {
        UUID id = UUID.randomUUID();
        WebhookEvent event = event(id, "ticket.purchased");
        processor.process(DownstreamProcessor.Consumer.TICKETS, event);
        processor.process(DownstreamProcessor.Consumer.ACCOUNTING, event);
        assertThat(count("ticket_records", id)).isEqualTo(1);
        assertThat(count("sales_entries", id)).isEqualTo(1);
        assertThat(count("notification_demo_failures", id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ticket.reserved", "reservation.expired", "payment.failed", "concert.sold_out"})
    void otherNotificationTypesDoNotSimulateFailure(String type) throws Exception {
        UUID id = UUID.randomUUID();
        processor.process(DownstreamProcessor.Consumer.NOTIFICATIONS, event(id, type));
        assertThat(count("simulated_notifications", id)).isEqualTo(1);
        assertThat(count("notification_demo_failures", id)).isZero();
    }

    private WebhookEvent event(UUID id, String type) throws Exception {
        return WebhookEvent.parse(mapper.writeValueAsBytes(Map.of("_eventrush",
                Map.of("id", id, "type", type, "version", 1), "reservationId", id,
                "paymentId", id, "concertId", id, "seatId", id, "userId", id, "amount", 25)));
    }

    private int count(String table, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table + " where event_id = ?", Integer.class, id);
    }
}
