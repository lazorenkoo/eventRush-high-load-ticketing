package com.lazorenkoo.eventrush.purchase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
class ParticipantReservationIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ParticipantReservationService service;
    @MockitoSpyBean private ParticipantService participants;
    private UUID owner;
    private UUID gate;
    private UUID concert;
    private UUID seat;
    private UUID participant;

    @BeforeEach
    void setup() {
        owner = UUID.randomUUID();
        gate = UUID.randomUUID();
        concert = jdbc.queryForObject("""
                insert into concerts(name, starts_at, sale_starts_at, crowdgate_gate_id, status)
                values ('Named reservation',now()+interval '1 day',now(),?,'ON_SALE') returning id
                """, UUID.class, gate);
        seat = jdbc.queryForObject("""
                insert into seats(concert_id,section,seat_row,seat_number,price)
                values (?,'A','1',1,25) returning id
                """, UUID.class, concert);
        participant = participants.createManual(owner, concert, seat, "First", "Last");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from outgoing_events where payload::jsonb->>'concertId'=?", concert.toString());
        jdbc.update("delete from participant_activity where participant_id in "
                + "(select id from purchase_participants where concert_id=?)", concert);
        jdbc.update("delete from purchase_participants where concert_id=?", concert);
        jdbc.update("delete from reservations where seat_id=?", seat);
        jdbc.update("delete from seats where concert_id=?", concert);
        jdbc.update("delete from concerts where id=?", concert);
    }

    @Test
    void concurrentReplaysCreateOneReservationAndEvent() throws Exception {
        var barrier = new CyclicBarrier(8);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 8).mapToObj(i -> (Callable<UUID>) () -> {
                barrier.await();
                return service.reserve(participant, seat, owner, gate).id();
            }).toList();
            var results = pool.invokeAll(tasks);
            UUID first = results.getFirst().get();
            for (var result : results) assertThat(result.get()).isEqualTo(first);
            assertThat(link()).isEqualTo(first);
        }
        assertThat(count("reservations", "seat_id", seat)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outgoing_events where payload::jsonb->>'concertId'=?",
                Integer.class, concert.toString())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "seat", "gate", "participant"})
    void mismatchedIdentityCannotChangeInventory(String mismatch) {
        assertThatThrownBy(() -> service.reserve(
                mismatch.equals("participant") ? UUID.randomUUID() : participant,
                mismatch.equals("seat") ? UUID.randomUUID() : seat,
                mismatch.equals("owner") ? UUID.randomUUID() : owner,
                mismatch.equals("gate") ? UUID.randomUUID() : gate))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(link()).isNull();
        assertThat(count("reservations", "seat_id", seat)).isZero();
    }

    @Test
    void linkFailureRollsBackSeatReservationAndOutbox() {
        doThrow(new IllegalStateException("Forced link failure"))
                .when(participants).linkReservation(any(), any(), any());
        assertThatThrownBy(() -> service.reserve(participant, seat, owner, gate))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select status from seats where id=?", String.class, seat))
                .isEqualTo("AVAILABLE");
        assertThat(link()).isNull();
        assertThat(count("reservations", "seat_id", seat)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outgoing_events where payload::jsonb->>'concertId'=?",
                Integer.class, concert.toString())).isZero();
    }

    private UUID link() {
        return jdbc.queryForObject("select reservation_id from purchase_participants where id=?", UUID.class, participant);
    }

    private int count(String table, String field, UUID value) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + field + "=?", Integer.class, value);
    }
}
