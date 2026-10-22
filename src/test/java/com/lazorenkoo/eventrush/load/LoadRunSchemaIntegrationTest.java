package com.lazorenkoo.eventrush.load;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
@Transactional
class LoadRunSchemaIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    private UUID concert;
    private UUID seat;
    private UUID run;
    private UUID client;

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id)
                values ('Load schema',now()+interval '1 day',now(),?) returning id
                """, UUID.class, UUID.randomUUID());
        seat = jdbc.queryForObject("""
                insert into seats(concert_id,section,seat_row,seat_number,price)
                values (?,'A','1',1,25) returning id
                """, UUID.class, concert);
        run = jdbc.queryForObject("""
                insert into load_test_runs(concert_id,participant_count,input_sha256) values (?,50000,?) returning id
                """, UUID.class, concert, "a".repeat(64));
        client = insertClient(1);
    }

    private UUID insertClient(int ordinal) {
        return jdbc.queryForObject("""
                insert into load_test_clients(run_id,concert_id,ordinal,first_name,last_name,seat_id)
                values (?,?,?,'First','Last',?) returning id
                """, UUID.class, run, concert, ordinal, seat);
    }

    @ParameterizedTest
    @ValueSource(strings = {"participant_count=0", "participant_count=50001", "concurrency=0", "concurrency=101",
            "input_sha256='invalid'", "status='UNKNOWN'", "status='RUNNING'", "status='COMPLETED'",
            "started_at=clock_timestamp()", "created_at=clock_timestamp()+interval '1 day',status='RUNNING',started_at=clock_timestamp()"})
    void rejectsInvalidRunBoundsAndLifecycle(String assignment) {
        assertThatThrownBy(() -> jdbc.update("update load_test_runs set " + assignment + " where id=?", run))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ordinal=0", "ordinal=50001", "first_name=''", "last_name=' Last'",
            "first_name=chr(10)", "status='UNKNOWN'", "status='SUCCEEDED'", "error_code='BAD'",
            "status='FAILED',finished_at=clock_timestamp(),error_code='raw customer details'"})
    void rejectsInvalidClientData(String assignment) {
        assertThatThrownBy(() -> jdbc.update("update load_test_clients set " + assignment + " where id=?", client))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsBoundaryOrdinalCompetingSeatsAndCompletedLifecycle() {
        insertClient(50000);
        jdbc.update("update load_test_runs set status='RUNNING',started_at=clock_timestamp() where id=?", run);
        jdbc.update("update load_test_clients set status='SUCCEEDED',finished_at=clock_timestamp() where id=?", client);
        jdbc.update("update load_test_runs set status='COMPLETED',finished_at=clock_timestamp() where id=?", run);
        assertThat(jdbc.queryForObject("select count(*) from load_test_clients where run_id=?",
                Integer.class, run)).isEqualTo(2);
    }

    @Test
    void rejectsDuplicateOrdinal() {
        assertThatThrownBy(() -> insertClient(1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"run_id", "concert_id", "seat_id", "participant_id"})
    void rejectsMissingForeignIdentity(String column) {
        assertThatThrownBy(() -> jdbc.update("update load_test_clients set " + column + "=? where id=?",
                UUID.randomUUID(), client)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void participantLinkMustMatchTestRun(boolean matching) {
        UUID participant = jdbc.queryForObject("""
                insert into purchase_participants(user_id,concert_id,preferred_seat_id,first_name,last_name,source,run_id)
                values (?,?,?,'First','Last','TEST',?) returning id
                """, UUID.class, UUID.randomUUID(), concert, seat, matching ? run : UUID.randomUUID());
        if (matching) {
            assertThat(jdbc.update("update load_test_clients set participant_id=? where id=?", participant, client))
                    .isEqualTo(1);
        } else {
            assertThatThrownBy(() -> jdbc.update("update load_test_clients set participant_id=? where id=?",
                    participant, client)).isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}