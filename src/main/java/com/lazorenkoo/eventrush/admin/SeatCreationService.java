package com.lazorenkoo.eventrush.admin;

import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SeatCreationService {

    private final JdbcTemplate jdbc;

    public SeatCreationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(timeout = 10)
    public List<UUID> create(UUID concertId, List<SeatDefinition> seats) {
        if (seats == null || seats.isEmpty() || seats.size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SEAT_BATCH");
        }
        var states = jdbc.queryForList("select status from concerts where id = ? for update", String.class, concertId);
        if (states.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CONCERT_NOT_FOUND");
        }
        if (!"DRAFT".equals(states.getFirst())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CONCERT_NOT_DRAFT");
        }
        var ids = new ArrayList<UUID>();
        try {
            for (SeatDefinition seat : seats) {
                ids.add(jdbc.queryForObject("""
                        insert into seats (concert_id, section, seat_row, seat_number, price)
                        values (?, ?, ?, ?, ?) returning id
                        """, UUID.class, concertId, seat.section().strip(), seat.row().strip(), seat.number(), seat.price()));
            }
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SEAT_POSITION_ALREADY_EXISTS");
        }
        return List.copyOf(ids);
    }

    public record SeatDefinition(@NotBlank @Size(max = 64) String section, @NotBlank @Size(max = 32) String row,
                                 @Min(1) int number, @NotNull @DecimalMin("0.01")
                                 @Digits(integer = 10, fraction = 2) BigDecimal price) {
    }
}
