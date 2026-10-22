package com.lazorenkoo.eventrush.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///downstream_processing_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
class DownstreamProcessorIntegrationTest {

    @Autowired
    private DownstreamProcessor processor;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;

    @ParameterizedTest
    @EnumSource(DownstreamProcessor.Consumer.class)
    void concurrentDuplicateDeliveriesCreateOneOutput(DownstreamProcessor.Consumer consumer) throws Exception {
        UUID id = UUID.randomUUID();
        WebhookEvent event = purchase(id, id, 25);
        var ready = new CountDownLatch(16);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(16)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                    processor.process(consumer, event);
                    return null;
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
        }
        String table = switch (consumer) {
            case TICKETS -> "ticket_records";
            case ACCOUNTING -> "sales_entries";
            case NOTIFICATIONS -> "simulated_notifications";
        };
        assertThat(count(table, id)).isEqualTo(1);
        assertThat(count("webhook_receipts", id)).isEqualTo(1);
    }

    @Test
    void consumersAreIndependentAndChangedRetryCannotChangeCapturedRevenue() throws Exception {
        UUID id = UUID.randomUUID();
        for (var consumer : DownstreamProcessor.Consumer.values()) processor.process(consumer, purchase(id, id, 25));
        assertThat(count("webhook_receipts", id)).isEqualTo(3);
        assertThatThrownBy(() -> processor.process(DownstreamProcessor.Consumer.ACCOUNTING, purchase(id, id, 30)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
        assertThat(jdbc.queryForObject("select amount from sales_entries where event_id = ?",
                java.math.BigDecimal.class, id)).isEqualByComparingTo("25.00");
    }

    @Test
    void validationFailureRollsBackReceiptAndAllowsCorrectedRedelivery() throws Exception {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> processor.process(DownstreamProcessor.Consumer.ACCOUNTING, purchase(id, id, 0)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(count("webhook_receipts", id)).isZero();
        processor.process(DownstreamProcessor.Consumer.ACCOUNTING, purchase(id, id, 25));
        assertThat(count("sales_entries", id)).isEqualTo(1);
    }

    @Test
    void outputConstraintFailureAlsoRollsBackTheReceipt() throws Exception {
        UUID first = UUID.randomUUID();
        UUID retry = UUID.randomUUID();
        processor.process(DownstreamProcessor.Consumer.TICKETS, purchase(first, first, 25));
        assertThatThrownBy(() -> processor.process(DownstreamProcessor.Consumer.TICKETS, purchase(retry, first, 25)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("webhook_receipts", retry)).isZero();
        assertThat(count("ticket_records", first)).isEqualTo(1);
    }

    private WebhookEvent purchase(UUID event, UUID reservation, int amount) throws Exception {
        return WebhookEvent.parse(mapper.writeValueAsBytes(Map.of("_eventrush",
                Map.of("id", event, "type", "ticket.purchased", "version", 1), "reservationId", reservation,
                "paymentId", reservation, "concertId", reservation, "seatId", reservation,
                "userId", reservation, "amount", amount)));
    }

    private int count(String table, UUID event) {
        return jdbc.queryForObject("select count(*) from " + table + " where event_id = ?", Integer.class, event);
    }
}
