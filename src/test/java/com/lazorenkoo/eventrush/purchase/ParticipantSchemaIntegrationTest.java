package com.lazorenkoo.eventrush.purchase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class ParticipantSchemaIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    private UUID concert;
    private UUID seat;
    private UUID owner;

    @BeforeEach
    void setup() {
        owner = UUID.randomUUID();
        concert = jdbc.queryForObject("""
                insert into concerts(name, starts_at, sale_starts_at, crowdgate_gate_id)
                values ('Participant test', now() + interval '1 day', now(), ?) returning id
                """, UUID.class, UUID.randomUUID());
        seat = jdbc.queryForObject("""
                insert into seats(concert_id, section, seat_row, seat_number, price)
                values (?, 'A', '1', 1, 25) returning id
                """, UUID.class, concert);
    }

    private UUID insert(String first, String last, String source, UUID run, UUID reservation) {
        return jdbc.queryForObject("""
                insert into purchase_participants(user_id, concert_id, preferred_seat_id,
                    first_name, last_name, source, run_id, reservation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, owner, concert, seat, first, last, source, run, reservation);
    }

    @Test
    void storesUnicodeNamesBeforeAdmissionAndLinksOwnedReservation() {
        UUID participant = insert("Łukasz", "O’Neil", "MANUAL", null, null);
        UUID reservation = reservation(owner, seat);
        assertThat(jdbc.update("update purchase_participants set reservation_id = ? where id = ?",
                reservation, participant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select first_name from purchase_participants where id = ?",
                String.class, participant)).isEqualTo("Łukasz");
    }

    @ParameterizedTest
    @CsvSource(value = {"''|Last", "First|''", "' First'|Last", "First|'Last '", "NULL|Last", "First|NULL"},
            delimiter = '|', nullValues = "NULL", ignoreLeadingAndTrailingWhitespace = false)
    void rejectsBlankUntrimmedOrNullNames(String first, String last) {
        assertThatThrownBy(() -> insert(first, last, "MANUAL", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"first", "last"})
    void rejectsControlCharactersAndOversizedNames(String field) {
        String invalid = field.equals("first") ? "A\nB" : "x".repeat(101);
        assertThatThrownBy(() -> insert(field.equals("first") ? invalid : "First",
                field.equals("last") ? invalid : "Last", "MANUAL", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @CsvSource({"MANUAL,true", "TEST,false", "OTHER,false"})
    void enforcesSourceRunRelationship(String source, boolean withRun) {
        assertThatThrownBy(() -> insert("First", "Last", source, withRun ? UUID.randomUUID() : null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsTestParticipantWithRunIdentity() {
        assertThat(insert("First", "Last", "TEST", UUID.randomUUID(), null)).isNotNull();
    }

    @Test
    void rejectsSeatFromAnotherConcert() {
        concert = jdbc.queryForObject(
                "insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id) select name,starts_at,sale_starts_at,gen_random_uuid() from concerts where id = ? returning id",
                UUID.class, concert);
        assertThatThrownBy(() -> insert("First", "Last", "MANUAL", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReservationOwnedByAnotherVisitor() {
        UUID reservation = reservation(UUID.randomUUID(), seat);
        assertThatThrownBy(() -> insert("First", "Last", "MANUAL", null, reservation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reservationCannotBeLinkedTwice() {
        UUID reservation = reservation(owner, seat);
        insert("First", "Last", "MANUAL", null, reservation);
        assertThatThrownBy(() -> insert("Other", "Visitor", "MANUAL", null, reservation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID reservation(UUID user, UUID seatId) {
        return jdbc.queryForObject("insert into reservations(user_id,seat_id,expires_at) values (?,?,now()+interval '5 minutes') returning id",
                UUID.class, user, seatId);
    }
}
