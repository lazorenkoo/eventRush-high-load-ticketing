package com.lazorenkoo.eventrush.relayforge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

@Service
public class OutgoingEvents {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean includeIdentity;

    public OutgoingEvents(JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${eventrush.events.include-identity:false}") boolean includeIdentity) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.includeIdentity = includeIdentity;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String type, Map<String, ?> payload) {
        UUID id = UUID.randomUUID();
        Map<String, ?> body = payload;
        if (includeIdentity) {
            if (payload.containsKey("_eventrush")) {
                throw new IllegalArgumentException("Event payload contains reserved metadata");
            }
            var identified = new LinkedHashMap<String, Object>(payload);
            identified.put("_eventrush", Map.of("id", id, "type", type, "version", 1));
            body = identified;
        }
        try {
            jdbc.update("insert into outgoing_events (id, event_type, payload) values (?, ?, ?)",
                    id, type, mapper.writeValueAsString(body));
            return id;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Event payload cannot be serialized", exception);
        }
    }
}
