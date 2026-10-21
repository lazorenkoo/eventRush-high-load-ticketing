package com.lazorenkoo.eventrush.purchase;

import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "eventrush.crowdgate.enabled=true", "eventrush.crowdgate.base-url=http://localhost:8080"
})
class JournalCaptureIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ParticipantRegistrationService registrations;
    @Autowired private ParticipantReservationService reservations;
    @MockitoBean private CrowdGateClient crowdgate;
    @MockitoSpyBean private ParticipantActivity journal;
    private ParticipantActivity journalTarget;
    private UUID owner;
    private UUID gate;
    private UUID concert;
    private UUID seat;
    private String token;

    @BeforeEach
    void setup() {
        journalTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(journal);
        owner = UUID.randomUUID();
        gate = UUID.randomUUID();
        token = gate + ":" + UUID.randomUUID();
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id,status)
                values ('Capture test',now()+interval '1 day',now(),?,'ON_SALE') returning id
                """, UUID.class, gate);
        seat = jdbc.queryForObject("""
                insert into seats(concert_id,section,seat_row,seat_number,price)
                values (?,'A','1',1,25) returning id
                """, UUID.class, concert);
        when(crowdgate.requireQueueOwner(gate, owner, token)).thenReturn("WAITING");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from participant_activity where participant_id in "
                + "(select id from purchase_participants where concert_id=?)", concert);
        jdbc.update("delete from purchase_participants where concert_id=?", concert);
        jdbc.update("delete from outgoing_events where payload::jsonb->>'concertId'=?", concert.toString());
        jdbc.update("delete from reservations where seat_id=?", seat);
        jdbc.update("delete from seats where concert_id=?", concert);
        jdbc.update("delete from concerts where id=?", concert);
    }

    private UUID register() { return registrations.register(owner, concert, seat, token, "First", "Last"); }

    private int events(UUID participant) {
        return jdbc.queryForObject("select count(*) from participant_activity where participant_id=?",
                Integer.class, participant);
    }

    @Test
    void capturesVerifiedTransitionsWithoutReplayDuplicatesOrBackwardState() {
        UUID id = register();
        assertThat(register()).isEqualTo(id);
        assertThat(events(id)).isEqualTo(2);
        when(crowdgate.requireQueueOwner(gate, owner, token)).thenReturn("ADMITTED");
        register();
        when(crowdgate.requireQueueOwner(gate, owner, token)).thenReturn("WAITING");
        register();
        assertThat(events(id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select queue_state from purchase_participants where id=?",
                String.class, id)).isEqualTo("ADMITTED");
    }

    @Test
    void registrationJournalFailureRollsBackNewParticipant() {
        doThrow(new IllegalStateException("Journal unavailable")).when(journalTarget)
                .append(any(), any(), eq(ParticipantActivity.Kind.REGISTRATION), anyString());
        assertThatThrownBy(this::register).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from purchase_participants where concert_id=?",
                Integer.class, concert)).isZero();
    }

    @Test
    void reservationCaptureIsAtomicAndReplayDoesNotDuplicateIt() {
        UUID id = register();
        UUID reservation = reservations.reserve(id, seat, owner, gate).id();
        assertThat(reservations.reserve(id, seat, owner, gate).id()).isEqualTo(reservation);
        assertThat(events(id)).isEqualTo(3);
    }

    @Test
    void reservationJournalFailureRollsBackCoreChanges() {
        UUID id = register();
        doThrow(new IllegalStateException("Journal unavailable")).when(journalTarget)
                .append(any(), any(), eq(ParticipantActivity.Kind.RESERVATION), anyString());
        assertThatThrownBy(() -> reservations.reserve(id, seat, owner, gate)).isInstanceOf(IllegalStateException.class);
        assertThat(events(id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from seats where id=?", String.class, seat)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from reservations where seat_id=?", Integer.class, seat)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outgoing_events where payload::jsonb->>'concertId'=?",
                Integer.class, concert.toString())).isZero();
    }
}
