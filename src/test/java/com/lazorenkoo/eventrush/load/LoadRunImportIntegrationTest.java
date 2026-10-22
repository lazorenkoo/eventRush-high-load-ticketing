package com.lazorenkoo.eventrush.load;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///load_import_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
class LoadRunImportIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoadRunImportService service;
    private UUID concert;
    private static final String HEADER = "firstName,lastName,seatNumber\n";

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id)
                values ('Import test',now()+interval '1 day',now(),?) returning id
                """, UUID.class, UUID.randomUUID());
        jdbc.update("insert into seats(concert_id,section,seat_row,seat_number,price) values(?,'A','1',300,25)", concert);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from load_test_clients where concert_id=?", concert);
        jdbc.update("delete from load_test_runs where concert_id=?", concert);
        jdbc.update("delete from seats where concert_id=?", concert);
        jdbc.update("delete from concerts where id=?", concert);
    }

    private LoadRunImportService.Imported load(UUID target, int concurrency, String csv) {
        return service.importCsv(target, concurrency, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private void noRuns() {
        assertThat(jdbc.queryForObject("select count(*) from load_test_runs where concert_id=?",
                Integer.class, concert)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from load_test_clients where concert_id=?",
                Integer.class, concert)).isZero();
    }

    @Test
    void preservesCompetingRowsOrderHashAndReadyState() throws Exception {
        String csv = HEADER + "Alice,Smith,300\nBob,Jones,300\n";
        var result = load(concert, 20, csv);
        assertThat(result.participantCount()).isEqualTo(2);
        assertThat(result.status()).isEqualTo("READY");
        assertThat(jdbc.queryForList("select first_name from load_test_clients where run_id=? order by ordinal",
                String.class, result.id())).containsExactly("Alice", "Bob");
        assertThat(jdbc.queryForObject("select input_sha256 from load_test_runs where id=?", String.class, result.id()))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(csv.getBytes(StandardCharsets.UTF_8))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "AMBIGUOUS", "CLOSED", "FOREIGN", "MALFORMED"})
    void invalidInputsLeaveNoPartialRun(String mode) {
        if (mode.equals("AMBIGUOUS")) jdbc.update("insert into seats(concert_id,section,seat_row,seat_number,price) "
                + "values(?,'B','1',300,25)", concert);
        if (mode.equals("CLOSED")) jdbc.update("update concerts set status='CANCELLED' where id=?", concert);
        UUID target = mode.equals("FOREIGN") ? UUID.randomUUID() : concert;
        String row = mode.equals("MISSING") ? "A,B,301" : mode.equals("MALFORMED") ? "A,B,0" : "A,B,300";
        assertThatThrownBy(() -> load(target, 20, HEADER + row)).isInstanceOf(ResponseStatusException.class);
        noRuns();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void rejectsInvalidConcurrency(int concurrency) {
        assertThatThrownBy(() -> load(concert, concurrency, HEADER + "A,B,300"))
                .isInstanceOf(ResponseStatusException.class);
        noRuns();
    }

    @Test
    void laterBatchFailureRollsBackFirstBatchAndRunMetadata() {
        jdbc.execute("alter table load_test_clients add constraint import_probe_failure check (ordinal <> 501)");
        try {
            assertThatThrownBy(() -> load(concert, 20, HEADER + "A,B,300\n".repeat(501)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            noRuns();
        } finally {
            jdbc.execute("alter table load_test_clients drop constraint import_probe_failure");
        }
    }
}