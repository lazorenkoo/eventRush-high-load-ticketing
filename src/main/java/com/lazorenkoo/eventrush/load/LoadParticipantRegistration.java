package com.lazorenkoo.eventrush.load;

import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient;
import com.lazorenkoo.eventrush.purchase.ParticipantActivity;
import com.lazorenkoo.eventrush.purchase.ParticipantService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "eventrush.crowdgate.enabled", havingValue = "true")
public class LoadParticipantRegistration {
    private final JdbcTemplate jdbc;
    private final CrowdGateClient crowdgate;
    private final ParticipantService participants;
    private final ParticipantActivity activity;
    private final LoadClientResults results;

    public LoadParticipantRegistration(JdbcTemplate jdbc, CrowdGateClient crowdgate, ParticipantService participants,
            ParticipantActivity activity, LoadClientResults results) {
        this.jdbc = jdbc;
        this.crowdgate = crowdgate;
        this.participants = participants;
        this.activity = activity;
        this.results = results;
    }

    @Transactional(timeout = 10)
    public UUID register(UUID client, UUID lease, String queueToken) {
        var stored = jdbc.query("""
                select c.run_id,c.concert_id,c.seat_id,c.first_name,c.last_name,c.participant_id,g.crowdgate_gate_id
                from load_test_clients c join load_test_runs r on r.id=c.run_id
                join concerts g on g.id=c.concert_id
                where c.id=? and c.lease_token=? and c.status='RUNNING' and c.lease_expires_at>clock_timestamp()
                    and r.status='RUNNING' and g.status='ON_SALE' for update of c
                """, (row, index) -> new Stored(row.getObject("run_id", UUID.class),
                row.getObject("concert_id", UUID.class), row.getObject("seat_id", UUID.class),
                row.getString("first_name"), row.getString("last_name"), row.getObject("participant_id", UUID.class),
                row.getObject("crowdgate_gate_id", UUID.class)), client, lease).stream().findFirst()
                .orElseThrow(() -> conflict("LOAD_CLIENT_NOT_ACTIVE"));
        String state = crowdgate.requireQueueOwner(stored.gate(), client, queueToken);
        String key = digest(queueToken + "|" + client);
        UUID participant = stored.participant();
        if (participant == null) {
            participant = participants.createTest(client, stored.concert(), stored.seat(),
                    stored.first(), stored.last(), stored.run());
            jdbc.update("update purchase_participants set queue_key=? where id=?", key, participant);
        } else {
            String previous = jdbc.queryForObject("select queue_key from purchase_participants where id=?",
                    String.class, participant);
            if (!key.equals(previous)) throw conflict("PARTICIPANT_ALREADY_REGISTERED");
        }
        if (!results.link(client, lease, participant)) throw conflict("LOAD_CLIENT_NOT_ACTIVE");
        activity.append(participant, participant, ParticipantActivity.Kind.REGISTRATION, "REGISTERED");
        activity.observeQueue(participant, client, ParticipantActivity.QueueState.valueOf(state));
        return participant;
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    private record Stored(UUID run, UUID concert, UUID seat, String first, String last, UUID participant, UUID gate) {
        @Override
        public String toString() {
            return "Stored[redacted]";
        }
    }
}
