package com.lazorenkoo.eventrush.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///journal_cursor_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
class JournalCursorConcurrencyIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ParticipantJournalController controller;
    private UUID concert;
    private UUID participant;

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id)
                values ('Cursor race',now()+interval '1 day',now(),?) returning id
                """, UUID.class, UUID.randomUUID());
        UUID seat = jdbc.queryForObject("""
                insert into seats(concert_id,section,seat_row,seat_number,price)
                values (?,'A','1',1,25) returning id
                """, UUID.class, concert);
        participant = jdbc.queryForObject("""
                insert into purchase_participants(user_id,concert_id,preferred_seat_id,first_name,last_name,source)
                values (?,?,?,'First','Last','MANUAL') returning id
                """, UUID.class, UUID.randomUUID(), concert, seat);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from participant_activity where participant_id=?", participant);
        jdbc.update("delete from purchase_participants where id=?", participant);
        jdbc.update("delete from seats where concert_id=?", concert);
        jdbc.update("delete from concerts where id=?", concert);
    }

    private long insert(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("""
                insert into participant_activity(participant_id,event_id,kind,state)
                values (?,?,'QUEUE','WAITING') returning id
                """)) {
            statement.setObject(1, participant);
            statement.setObject(2, UUID.randomUUID());
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cursorWaitsForEarlierInsertBeforeAdvancing(boolean rollback) throws Exception {
        try (var earlier = dataSource.getConnection(); var later = dataSource.getConnection();
                var pool = Executors.newSingleThreadExecutor()) {
            earlier.setAutoCommit(false);
            long low = insert(earlier);
            long high = insert(later);
            var page = pool.submit(() -> controller.list(concert, 0, 50, "", "", "").getBody());
            try {
                await().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofMillis(700)).until(() ->
                        jdbc.queryForObject("select count(*) from pg_locks where "
                                + "relation='participant_activity'::regclass and mode='ShareLock' and not granted",
                                Integer.class) > 0);
            } finally {
                if (rollback) earlier.rollback();
                else earlier.commit();
            }
            var result = page.get(3, TimeUnit.SECONDS);
            assertThat(result.items()).extracting(ParticipantJournalController.Entry::id)
                    .containsExactlyElementsOf(rollback ? java.util.List.of(high) : java.util.List.of(low, high));
            assertThat(result.nextAfter()).isEqualTo(high);
            assertThat(controller.list(concert, high, 50, "", "", "").getBody().items()).isEmpty();
        }
    }

    @Test
    void lockTimeoutDoesNotReturnAnUnsafeCursorAndNextReadRecovers() throws Exception {
        try (var earlier = dataSource.getConnection()) {
            earlier.setAutoCommit(false);
            insert(earlier);
            assertThatThrownBy(() -> controller.list(concert, 0, 50, "", "", ""))
                    .isInstanceOfSatisfying(UncategorizedSQLException.class, exception ->
                            assertThat(exception.getSQLException().getSQLState()).isEqualTo("55P03"));
            earlier.rollback();
            assertThat(controller.list(concert, 0, 50, "", "", "").getBody().nextAfter()).isZero();
        }
    }
}