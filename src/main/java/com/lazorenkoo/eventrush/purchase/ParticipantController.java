package com.lazorenkoo.eventrush.purchase;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "eventrush.crowdgate.enabled", havingValue = "true")
public class ParticipantController {
    private final ParticipantRegistrationService registrations;

    public ParticipantController(ParticipantRegistrationService registrations) {
        this.registrations = registrations;
    }

    @PostMapping("/api/v1/participants")
    public ResponseEntity<ParticipantId> register(@Valid @RequestBody Registration body, HttpServletRequest request) {
        UUID id = registrations.register(visitor(request), body.concertId(), body.seatId(),
                body.queueToken(), body.firstName(), body.lastName());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new ParticipantId(id));
    }

    private UUID visitor(HttpServletRequest request) {
        var all = request.getCookies();
        var visitors = all == null ? java.util.List.<jakarta.servlet.http.Cookie>of()
                : Arrays.stream(all).filter(cookie -> "cg_user_id".equals(cookie.getName())).toList();
        if (visitors.size() == 1) {
            try {
                String value = visitors.getFirst().getValue();
                UUID id = UUID.fromString(value);
                if (id.toString().equals(value)) return id;
            } catch (IllegalArgumentException error) {
                // A malformed visitor cookie cannot establish queue ownership.
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "CROWDGATE_VISITOR_REQUIRED");
    }

    public record Registration(@NotNull UUID concertId, @NotNull UUID seatId,
                               @NotBlank @Size(max = 73) String queueToken,
                               @NotBlank @Size(max = 200) String firstName,
                               @NotBlank @Size(max = 200) String lastName) {
        @JsonAnySetter
        public void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("Unknown participant field");
        }

        @Override
        public String toString() {
            return "Registration[concertId=" + concertId + ", seatId=" + seatId + "]";
        }
    }

    public record ParticipantId(UUID id) {
    }
}
