package com.lazorenkoo.eventrush.purchase;

import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient;
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
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "eventrush.crowdgate.enabled", havingValue = "true")
public class ParticipantRegistrationService {
    private final JdbcTemplate jdbc;
    private final CrowdGateClient crowdgate;
    private final ParticipantActivity activity;

    public ParticipantRegistrationService(JdbcTemplate jdbc, CrowdGateClient crowdgate, ParticipantActivity activity) {
        this.jdbc = jdbc;
        this.crowdgate = crowdgate;
        this.activity = activity;
    }

    @Transactional
    public UUID register(UUID visitor, UUID concert, UUID seat, String queueToken, String first, String last) {
        Objects.requireNonNull(visitor, "visitor");
        String firstName = ParticipantService.name(first);
        String lastName = ParticipantService.name(last);
        var gates = jdbc.queryForList("""
                select c.crowdgate_gate_id from concerts c join seats s on s.concert_id = c.id
                where c.id = ? and s.id = ? and c.status = 'ON_SALE'
                """, UUID.class, concert, seat);
        if (gates.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PURCHASE_SEAT_UNAVAILABLE");
        String queueState = crowdgate.requireQueueOwner(gates.getFirst(), visitor, queueToken);
        String queueKey = digest(queueToken + "|" + visitor);
        var ids = jdbc.queryForList("""
                insert into purchase_participants(user_id, concert_id, preferred_seat_id,
                    first_name, last_name, source, queue_key)
                values (?, ?, ?, ?, ?, 'MANUAL', ?)
                on conflict (queue_key) where queue_key is not null do update
                set queue_key = excluded.queue_key
                where purchase_participants.user_id = excluded.user_id
                    and purchase_participants.concert_id = excluded.concert_id
                    and purchase_participants.preferred_seat_id = excluded.preferred_seat_id
                    and purchase_participants.first_name = excluded.first_name
                    and purchase_participants.last_name = excluded.last_name
                    and purchase_participants.source = 'MANUAL'
                returning id
                """, UUID.class, visitor, concert, seat, firstName, lastName, queueKey);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "PARTICIPANT_ALREADY_REGISTERED");
        UUID participant = ids.getFirst();
        activity.append(participant, participant, ParticipantActivity.Kind.REGISTRATION, "REGISTERED");
        activity.observeQueue(participant, visitor, ParticipantActivity.QueueState.valueOf(queueState));
        return participant;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
