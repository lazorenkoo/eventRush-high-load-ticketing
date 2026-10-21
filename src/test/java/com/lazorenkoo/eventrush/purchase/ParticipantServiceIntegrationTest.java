package com.lazorenkoo.eventrush.purchase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
@Transactional
class ParticipantServiceIntegrationTest {
    @Autowired private ParticipantService service;
    @Autowired private JdbcTemplate jdbc;
    private UUID owner;
    private UUID concert;
    private UUID seat;
    private UUID otherSeat;

    @BeforeEach
    void setup() {
        owner = UUID.randomUUID();
        concert = jdbc.queryForObject("""
                insert into concerts(name, starts_at, sale_starts_at, crowdgate_gate_id, status)
                values ('Service test', now()+interval '1 day', now(), ?, 'ON_SALE') returning id
                """, UUID.class, UUID.randomUUID());
        var seats = jdbc.queryForList("""
                insert into seats(concert_id, section, seat_row, seat_number, price)
                select ?, 'A', '1', n, 25 from generate_series(1,2) n returning id
                """, UUID.class, concert);
        seat = seats.getFirst();
        otherSeat = seats.getLast();
    }

    @Test
    void normalizesNamesAndPreservesManualOrTestSource() {
        UUID id = create("  Łukasz  ");
        assertThat(jdbc.queryForObject("select first_name from purchase_participants where id=?",
                String.class, id)).isEqualTo("Łukasz");
        UUID run = UUID.randomUUID();
        UUID test = service.createTest(owner, concert, seat, "Test", "Visitor", run);
        assertThat(jdbc.queryForObject("select run_id from purchase_participants where id=?",
                UUID.class, test)).isEqualTo(run);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "A\nB", "A\tB", "A\u007fB"})
    void invalidNamesCreateNoParticipant(String name) {
        rejected(() -> create(name), 400);
        assertThat(jdbc.queryForObject("select count(*) from purchase_participants", Integer.class)).isZero();
    }

    @Test
    void rejectsNullAndOverlengthNames() {
        rejected(() -> create(null), 400);
        rejected(() -> create("x".repeat(101)), 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "SOLD_OUT"})
    void closedConcertDoesNotCreateParticipant(String status) {
        jdbc.update("update concerts set status=? where id=?", status, concert);
        rejected(() -> create("First"), 404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "seat", "missing"})
    void invalidLinkLeavesParticipantUnchanged(String mismatch) {
        UUID participant = create("First");
        UUID reservation = reservation(mismatch.equals("owner") ? UUID.randomUUID() : owner,
                mismatch.equals("seat") ? otherSeat : seat);
        rejected(() -> service.linkReservation(participant, owner,
                mismatch.equals("missing") ? UUID.randomUUID() : reservation), 404);
        assertThat(jdbc.queryForObject("select reservation_id from purchase_participants where id=?",
                UUID.class, participant)).isNull();
    }

    @Test
    void linkIsIdempotentButCannotBeReassignedOrClaimed() {
        UUID participant = create("First");
        UUID first = reservation(owner, seat);
        service.linkReservation(participant, owner, first);
        service.linkReservation(participant, owner, first);
        rejected(() -> service.linkReservation(participant, UUID.randomUUID(), first), 404);
        jdbc.update("update reservations set status='EXPIRED' where id=?", first);
        UUID second = reservation(owner, seat);
        rejected(() -> service.linkReservation(participant, owner, second), 404);
        assertThat(jdbc.queryForObject("select reservation_id from purchase_participants where id=?",
                UUID.class, participant)).isEqualTo(first);
    }

    private UUID create(String first) { return service.createManual(owner, concert, seat, first, "Last"); }

    private UUID reservation(UUID visitor, UUID seatId) {
        return jdbc.queryForObject("""
                insert into reservations(user_id,seat_id,expires_at)
                values (?,?,now()+interval '5 minutes') returning id
                """, UUID.class, visitor, seatId);
    }

    private void rejected(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
    }
}
