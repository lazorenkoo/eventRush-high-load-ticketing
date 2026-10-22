package com.lazorenkoo.eventrush.load;

import com.lazorenkoo.eventrush.purchase.ParticipantService;
import com.lazorenkoo.eventrush.purchase.ParticipantReservationService;
import com.lazorenkoo.eventrush.payment.PaymentService;
import com.lazorenkoo.eventrush.payment.PaymentCompletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///client_results_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test"
})
class LoadClientPurchaseProofIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoadRunImportService imports;
    @Autowired private LoadRunLifecycleService lifecycle;
    @Autowired private LoadClientLeases leases;
    @Autowired private LoadClientResults results;
    @Autowired private ParticipantService participants;
    @Autowired private ParticipantReservationService reservations;
    @Autowired private PaymentService payments;
    @Autowired private PaymentCompletionService completion;
    private UUID concert;
    private UUID run;
    private UUID participant;
    private LoadClientLeases.Client client;

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id,status)
                values ('Client result',now()+interval '1 day',now(),?,'ON_SALE') returning id
                """, UUID.class, UUID.randomUUID());
        jdbc.update("insert into seats(concert_id,section,seat_row,seat_number,price) values(?,'A','1',300,25)", concert);
        run = imports.importCsv(concert, 1, new ByteArrayInputStream(
                "firstName,lastName,seatNumber\nFirst,Last,300".getBytes(java.nio.charset.StandardCharsets.UTF_8))).id();
        lifecycle.start(concert, run);
        client = leases.claim(run).getFirst();
        participant = participants.createTest(client.id(), concert, client.seatId(), "First", "Last", run);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from load_test_clients where run_id=?", run);
        jdbc.update("delete from participant_activity where participant_id=?", participant);
        jdbc.update("delete from purchase_participants where concert_id=?", concert);
        jdbc.update("delete from payments where reservation_id in (select id from reservations where seat_id=?)", client.seatId());
        jdbc.update("delete from reservations where seat_id=?", client.seatId());
        jdbc.update("delete from outgoing_events where payload::jsonb->>'concertId'=?", concert.toString());
        jdbc.update("delete from load_test_runs where id=?", run);
        jdbc.update("delete from seats where concert_id=?", concert);
        jdbc.update("delete from concerts where id=?", concert);
    }

    @ParameterizedTest
    @EnumSource(PaymentService.Mode.class)
    void successRequiresConfirmedReservationAndSuccessfulPayment(PaymentService.Mode mode) {
        assertThat(results.link(client.id(), client.leaseToken(), participant)).isTrue();
        UUID gate = jdbc.queryForObject("select crowdgate_gate_id from concerts where id=?", UUID.class, concert);
        var reservation = reservations.reserve(participant, client.seatId(), client.id(), gate);
        payments.start(reservation.id(), client.id(), mode);
        assertThat(results.finish(client.id(), client.leaseToken(), LoadClientResults.Outcome.SUCCEEDED, null)).isFalse();
        jdbc.update("update payments set complete_after=clock_timestamp()-interval '1 second' where reservation_id=?",
                reservation.id());
        completion.complete(reservation.id());
        boolean succeeded = mode != PaymentService.Mode.FAILED;
        assertThat(results.finish(client.id(), client.leaseToken(), LoadClientResults.Outcome.SUCCEEDED, null))
                .isEqualTo(succeeded);
        if (succeeded) {
            leases.reap(run);
            assertThat(jdbc.queryForObject("select status from load_test_runs where id=?", String.class, run))
                    .isEqualTo("COMPLETED");
        }
    }

    @Test
    void participantForAnotherSeatCannotLink() {
        UUID other = jdbc.queryForObject("insert into seats(concert_id,section,seat_row,seat_number,price) "
                + "values(?,'A','1',301,25) returning id", UUID.class, concert);
        jdbc.update("update purchase_participants set preferred_seat_id=? where id=?", other, participant);
        assertThat(results.link(client.id(), client.leaseToken(), participant)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "SOLD_OUT", "CANCELLED", "FAILED"})
    void failureCodeMustMatchOutcome(String state) {
        var outcome = LoadClientResults.Outcome.valueOf(state);
        var failure = outcome == LoadClientResults.Outcome.FAILED ? null : LoadClientResults.Failure.WORKER_ERROR;
        assertThatThrownBy(() -> results.finish(client.id(), client.leaseToken(), outcome, failure))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
