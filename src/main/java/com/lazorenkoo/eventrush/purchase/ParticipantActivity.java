package com.lazorenkoo.eventrush.purchase;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

@Service
public class ParticipantActivity {
    private final JdbcTemplate jdbc;

    public ParticipantActivity(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID participant, UUID event, Kind kind, String state) {
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(kind, "kind");
        if (state == null || !state.matches("[A-Z][A-Z0-9_]{0,31}")) {
            throw new IllegalArgumentException("Invalid activity state");
        }
        var ids = jdbc.queryForList("""
                insert into participant_activity(participant_id,event_id,kind,state)
                values (?,?,?,?) on conflict (participant_id,event_id,kind) do update
                set state = participant_activity.state where participant_activity.state = excluded.state
                returning id
                """, Long.class, participant, event, kind.name(), state);
        if (ids.isEmpty()) throw new IllegalStateException("Activity identity reused with different state");
    }

    @Transactional
    public void observeQueue(UUID participant, UUID owner, QueueState state) {
        Objects.requireNonNull(state, "state");
        var previous = jdbc.query("""
                select queue_state from purchase_participants where id = ? and user_id = ? for update
                """, (row, index) -> new Observation(row.getString("queue_state")), participant, owner);
        if (previous.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND");
        }
        String priorState = previous.getFirst().state();
        if (("ACTIVE".equals(priorState) && state != QueueState.ACTIVE)
                || ("ADMITTED".equals(priorState) && state == QueueState.WAITING)) return;
        jdbc.update("""
                update purchase_participants set queue_state = ?, queue_observed_at = clock_timestamp()
                where id = ? and user_id = ?
                """, state.name(), participant, owner);
        if (!state.name().equals(previous.getFirst().state())) {
            append(participant, UUID.randomUUID(), Kind.QUEUE, state.name());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void forReservation(UUID reservation, UUID event, Kind kind, String state) {
        var participants = jdbc.queryForList("select id from purchase_participants where reservation_id = ?",
                UUID.class, reservation);
        for (UUID participant : participants) append(participant, event, kind, state);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void failPendingPayment(UUID reservation) {
        var payments = jdbc.queryForList("""
                update payments set status = 'FAILED', completed_at = clock_timestamp()
                where reservation_id = ? and status = 'PENDING' returning id
                """, UUID.class, reservation);
        for (UUID payment : payments) {
            UUID event = UUID.nameUUIDFromBytes((payment + "|FAILED")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            forReservation(reservation, event, Kind.PAYMENT, "FAILED");
        }
    }

    public enum Kind {
        REGISTRATION, QUEUE, RESERVATION, PAYMENT, TICKET, ACCOUNTING, NOTIFICATION
    }

    public enum QueueState {
        WAITING, ADMITTED, ACTIVE
    }

    private record Observation(String state) {
    }
}
