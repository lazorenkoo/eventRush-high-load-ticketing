package com.lazorenkoo.eventrush.load;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

@Service
public class LoadRunImportService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final LoadCsvParser parser;

    public LoadRunImportService(JdbcTemplate jdbc, LoadCsvParser parser) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.parser = parser;
    }

    @Transactional(timeout = 30)
    public Imported importCsv(UUID concert, int concurrency, InputStream input) {
        if (concurrency < 1 || concurrency > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOAD_CONCURRENCY");
        }
        var parsed = parser.parse(input);
        var concerts = jdbc.queryForList("select status from concerts where id=? for share", String.class, concert);
        if (concerts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CONCERT_NOT_FOUND");
        }
        if (!java.util.Set.of("DRAFT", "ON_SALE").contains(concerts.getFirst())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CONCERT_NOT_IMPORTABLE");
        }
        var numbers = parsed.rows().stream().map(LoadCsvParser.Row::seatNumber).distinct().toList();
        var seats = named.query("""
                select seat_number, min(id::text)::uuid as id, count(*) as matches
                from seats where concert_id=:concert and seat_number in (:numbers) group by seat_number
                """, Map.of("concert", concert, "numbers", numbers),
                (row, index) -> new Seat(row.getInt("seat_number"), row.getObject("id", UUID.class),
                        row.getInt("matches")));
        var resolved = new HashMap<Integer, UUID>();
        for (Seat seat : seats) {
            if (seat.matches() != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AMBIGUOUS_LOAD_SEAT");
            }
            resolved.put(seat.number(), seat.id());
        }
        if (resolved.size() != numbers.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_LOAD_SEAT");
        }
        UUID run = jdbc.queryForObject("""
                insert into load_test_runs(concert_id,participant_count,concurrency,input_sha256)
                values (?,?,?,?) returning id
                """, UUID.class, concert, parsed.rows().size(), concurrency, parsed.sha256());
        jdbc.batchUpdate("""
                insert into load_test_clients(run_id,concert_id,ordinal,first_name,last_name,seat_id)
                values (?,?,?,?,?,?)
                """, IntStream.range(0, parsed.rows().size()).boxed().toList(), 500, (statement, index) -> {
            var row = parsed.rows().get(index);
            statement.setObject(1, run);
            statement.setObject(2, concert);
            statement.setInt(3, index + 1);
            statement.setString(4, row.firstName());
            statement.setString(5, row.lastName());
            statement.setObject(6, resolved.get(row.seatNumber()));
        });
        return new Imported(run, concert, parsed.rows().size(), concurrency, "READY");
    }

    public record Imported(UUID id, UUID concertId, int participantCount, int concurrency, String status) {
    }

    private record Seat(int number, UUID id, int matches) {
    }
}