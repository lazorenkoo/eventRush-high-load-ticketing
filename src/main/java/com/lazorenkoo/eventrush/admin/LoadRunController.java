package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.load.LoadRunImportService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@RestController
public class LoadRunController {
    private final LoadRunImportService imports;
    private final JdbcTemplate jdbc;

    public LoadRunController(LoadRunImportService imports, JdbcTemplate jdbc) {
        this.imports = imports;
        this.jdbc = jdbc;
    }

    @PostMapping(value = "/api/v1/admin/concerts/{concertId}/load-runs",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LoadRunImportService.Imported> upload(@PathVariable UUID concertId,
            @RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "20") int concurrency) {
        if (file.isEmpty() || file.getSize() > 16 * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOAD_CSV");
        }
        try (var input = file.getInputStream()) {
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                    .body(imports.importCsv(concertId, concurrency, input));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOAD_CSV");
        }
    }

    @GetMapping("/api/v1/admin/concerts/{concertId}/load-runs/{runId}")
    public ResponseEntity<Run> get(@PathVariable UUID concertId, @PathVariable UUID runId) {
        var run = jdbc.query("""
                select id,concert_id,participant_count,concurrency,status,created_at
                from load_test_runs where id=? and concert_id=?
                """, (row, index) -> new Run(row.getObject("id", UUID.class),
                row.getObject("concert_id", UUID.class), row.getInt("participant_count"),
                row.getInt("concurrency"), row.getString("status"), row.getTimestamp("created_at").toInstant()),
                runId, concertId).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "LOAD_RUN_NOT_FOUND"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(run);
    }

    public record Run(UUID id, UUID concertId, int participantCount, int concurrency,
                      String status, Instant createdAt) {
    }
}