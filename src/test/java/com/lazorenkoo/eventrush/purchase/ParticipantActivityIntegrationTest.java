package com.lazorenkoo.eventrush.purchase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
class ParticipantActivityIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ParticipantActivity journal;
    @Autowired private ParticipantService participants;
    @Autowired private TransactionTemplate tx;
    private UUID participant;
    private UUID owner;
    private UUID concert;

    @BeforeEach
    void setup() {
        owner = UUID.randomUUID();
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id,status)
                values ('Journal test',now()+interval '1 day',now(),?,'ON_SALE') returning id
                """, UUID.class, UUID.randomUUID());
        UUID seat = jdbc.queryForObject("""
                insert into seats(concert_id,section,seat_row,seat_number,price)
                values (?,'A','1',1,25) returning id
                """, UUID.class, concert);
        participant = participants.createManual(owner, concert, seat, "First", "Last");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from participant_activity where participant_id=?", participant);
        jdbc.update("delete from purchase_participants where id=?", participant);
        jdbc.update("delete from seats where concert_id=?", concert);
        jdbc.update("delete from concerts where id=?", concert);
    }

    private void append(UUID event, String state) {
        journal.append(participant, event, ParticipantActivity.Kind.REGISTRATION, state);
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from participant_activity where participant_id=?",
                Integer.class, participant);
    }

    @Test
    void requiresTransactionAndRollsBackWithCaller() {
        assertThatThrownBy(() -> append(UUID.randomUUID(), "REGISTERED"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            append(UUID.randomUUID(), "REGISTERED");
            throw new IllegalStateException("Caller failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();
    }

    @Test
    void concurrentEventReplaysDeduplicateAndConflictingStateIsRejected() throws Exception {
        UUID event = UUID.randomUUID();
        var barrier = new CyclicBarrier(8);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 8).mapToObj(i -> (Callable<Void>) () -> {
                barrier.await();
                tx.executeWithoutResult(status -> append(event, "REGISTERED"));
                return null;
            }).toList();
            for (var result : pool.invokeAll(tasks)) result.get();
        }
        assertThat(count()).isEqualTo(1);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> append(event, "CHANGED")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void repeatedObservationUpdatesTimestampWithoutDuplicateTransition() {
        journal.observeQueue(participant, owner, ParticipantActivity.QueueState.WAITING);
        var first = jdbc.queryForObject("select queue_observed_at from purchase_participants where id=?",
                java.sql.Timestamp.class, participant);
        journal.observeQueue(participant, owner, ParticipantActivity.QueueState.WAITING);
        assertThat(count()).isEqualTo(1);
        journal.observeQueue(participant, owner, ParticipantActivity.QueueState.ADMITTED);
        assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select queue_observed_at from purchase_participants where id=?",
                java.sql.Timestamp.class, participant)).isAfterOrEqualTo(first);
    }

    @Test
    void observationCannotBeAttributedToAnotherOwnerOrHavePartialTimestamp() {
        assertThatThrownBy(() -> journal.observeQueue(participant, UUID.randomUUID(), ParticipantActivity.QueueState.ACTIVE))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(count()).isZero();
        assertThatThrownBy(() -> jdbc.update("update purchase_participants set queue_observed_at=now() where id=?", participant))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
