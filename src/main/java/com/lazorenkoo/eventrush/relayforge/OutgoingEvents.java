package com.lazorenkoo.eventrush.relayforge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class OutgoingEvents {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutgoingEvents(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String type, Map<String, ?> payload) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into outgoing_events (id, event_type, payload) values (?, ?, ?)",
                    id, type, mapper.writeValueAsString(payload));
            return id;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Event payload cannot be serialized", exception);
        }
    }
}
