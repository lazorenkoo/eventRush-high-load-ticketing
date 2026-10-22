package com.lazorenkoo.eventrush.relayforge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_publisher_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test", "eventrush.expiration.enabled=false"
})
@Import(OutgoingEventPublisherIntegrationTest.Config.class)
class OutgoingEventPublisherIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OutgoingEventPublisher publisher;
    @Autowired
    private RelayForgeClient client;
    @Autowired
    private PlatformTransactionManager transactions;

    private final UUID id = UUID.randomUUID();
    private final String payload = "{ \"amount\": 10.00 }";

    @BeforeEach
    void insertCommittedEvent() {
        reset(client);
        jdbc.update("insert into outgoing_events (id, event_type, payload) values (?, 'ticket.purchased', ?)", id, payload);
    }

    @AfterEach
    void removeEvent() {
        jdbc.update("delete from outgoing_events where id = ?", id);
    }

    @Test
    void failureSchedulesRetryAndSuccessKeepsExactIdentityAndPayload() {
        doThrow(new IllegalStateException("unavailable")).doNothing()
                .when(client).publish(id, "ticket.purchased", payload);
        assertThat(publisher.publishNext()).isTrue();
        assertThat(publisher.publishNext()).isFalse();
        assertThat(jdbc.queryForObject("""
                select published_at is null and attempt_count = 1 and next_attempt_at > clock_timestamp()
                    and last_error = 'IllegalStateException' from outgoing_events where id = ?
                """, Boolean.class, id)).isTrue();
        jdbc.update("update outgoing_events set next_attempt_at = clock_timestamp() - interval '1 second' where id = ?", id);
        assertThat(publisher.publishNext()).isTrue();
        assertThat(publisher.publishNext()).isFalse();
        assertThat(jdbc.queryForObject("""
                select published_at is not null and attempt_count = 2 and last_error is null
                from outgoing_events where id = ?
                """, Boolean.class, id)).isTrue();
        verify(client, times(2)).publish(id, "ticket.purchased", payload);
        verifyNoMoreInteractions(client);
    }

    @Test
    void competingWorkerSkipsLockedEventWithoutSending() {
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            jdbc.queryForObject("select id from outgoing_events where id = ? for update", UUID.class, id);
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(publisher::publishNext).get(3, TimeUnit.SECONDS)).isFalse();
            } catch (Exception error) {
                throw new AssertionError("Publisher blocked or failed", error);
            }
            verifyNoInteractions(client);
        });
        assertThat(publisher.publishNext()).isTrue();
        verify(client).publish(id, "ticket.purchased", payload);
    }

    @Test
    void refusesToSendInsideBusinessTransaction() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(transaction -> publisher.publishNext()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(client);
    }

    @TestConfiguration
    static class Config {
        @Bean
        RelayForgeClient client() {
            return mock(RelayForgeClient.class);
        }

        @Bean
        OutgoingEventPublisher publisher(JdbcTemplate jdbc, RelayForgeClient client, PlatformTransactionManager manager) {
            return new OutgoingEventPublisher(jdbc, client, manager);
        }
    }
}
