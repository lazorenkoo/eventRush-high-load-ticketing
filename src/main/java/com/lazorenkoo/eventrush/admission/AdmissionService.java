package com.lazorenkoo.eventrush.admission;

import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "eventrush.crowdgate.enabled", havingValue = "true")
@Transactional(propagation = Propagation.NEVER)
public class AdmissionService {

    private final JdbcTemplate jdbc;
    private final CrowdGateClient crowdGate;
    private final AdmissionSessionStore sessions;

    public AdmissionService(JdbcTemplate jdbc, CrowdGateClient crowdGate, AdmissionSessionStore sessions) {
        this.jdbc = jdbc;
        this.crowdGate = crowdGate;
        this.sessions = sessions;
    }

    public String exchange(UUID concertId, UUID visitor, String admissionToken) {
        UUID gate = saleGate(concertId);
        var admission = crowdGate.redeem(gate, visitor, admissionToken);
        Instant expiresAt = crowdGate.validateSession(admission);
        return sessions.create(admission, expiresAt);
    }

    public UUID requireActiveOwner(String sessionToken, UUID concertId) {
        var session = sessions.require(sessionToken);
        UUID gate = saleGate(concertId);
        if (!gate.equals(session.admission().gateId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMISSION_GATE_MISMATCH");
        }
        crowdGate.validateSession(session.admission());
        return session.admission().visitorId();
    }

    public Instant heartbeat(String sessionToken) {
        var session = sessions.require(sessionToken);
        return crowdGate.heartbeat(session.admission());
    }

    private UUID saleGate(UUID concertId) {
        var gates = jdbc.query("""
                select crowdgate_gate_id, status = 'ON_SALE' and sale_starts_at <= clock_timestamp()
                    and starts_at > clock_timestamp() as sale_open
                from concerts where id = ?
                """, (row, index) -> new Gate(row.getObject("crowdgate_gate_id", UUID.class),
                row.getBoolean("sale_open")), concertId);
        if (gates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CONCERT_NOT_FOUND");
        }
        if (!gates.getFirst().saleOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CONCERT_NOT_ON_SALE");
        }
        return gates.getFirst().id();
    }

    private record Gate(UUID id, boolean saleOpen) {
    }
}
