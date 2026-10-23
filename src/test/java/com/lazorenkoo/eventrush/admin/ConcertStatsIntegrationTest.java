package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
import com.lazorenkoo.eventrush.payment.PaymentCompletionService;
import com.lazorenkoo.eventrush.payment.PaymentService;
import com.lazorenkoo.eventrush.reservation.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
@Transactional
class ConcertStatsIntegrationTest {

    private static final String KEY = "test-only-admin-key-" + "a".repeat(32);
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ConcertStatsController controller;
    @Autowired
    private ReservationService reservations;
    @Autowired
    private PaymentService payments;
    @Autowired
    private PaymentCompletionService completion;

    private final UUID gate = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private UUID concert;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        concert = jdbc.queryForObject("""
                insert into concerts (name, starts_at, sale_starts_at, status, crowdgate_gate_id)
                values ('Stats show', now() + interval '1 day', now(), 'ON_SALE', ?) returning id
                """, UUID.class, gate);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new AdminAuthenticationFilter(KEY)).build();
    }

    @Test
    void emptyConcertReturnsZeroCountsAndRevenue() throws Exception {
        mvc.perform(get(path(concert)).header("X-Admin-Key", KEY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(0)).andExpect(jsonPath("$.available").value(0))
                .andExpect(jsonPath("$.reserved").value(0)).andExpect(jsonPath("$.sold").value(0))
                .andExpect(jsonPath("$.revenue").value(0)).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void paymentHistoryDoesNotMultiplySeatsOrUseChangedSeatPriceAsRevenue() throws Exception {
        var seats = new ArrayList<UUID>();
        for (int number = 1; number <= 3; number++) {
            seats.add(jdbc.queryForObject("""
                    insert into seats (concert_id, section, seat_row, seat_number, price)
                    values (?, 'A', '1', ?, 10) returning id
                    """, UUID.class, concert, number));
        }
        UUID purchased = reservations.reserve(seats.get(0), owner, gate).id();
        payments.start(purchased, owner, PaymentService.Mode.SUCCESS);
        completion.complete(purchased);
        UUID failed = reservations.reserve(seats.get(1), owner, gate).id();
        payments.start(failed, owner, PaymentService.Mode.FAILED);
        completion.complete(failed);
        UUID retry = reservations.reserve(seats.get(1), owner, gate).id();
        payments.start(retry, owner, PaymentService.Mode.DELAYED_SUCCESS);
        jdbc.update("update seats set price = 99 where id = ?", seats.get(0));
        mvc.perform(get(path(concert)).header("X-Admin-Key", KEY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(3)).andExpect(jsonPath("$.available").value(1))
                .andExpect(jsonPath("$.reserved").value(1)).andExpect(jsonPath("$.sold").value(1))
                .andExpect(jsonPath("$.pendingPayments").value(1))
                .andExpect(jsonPath("$.successfulPayments").value(1))
                .andExpect(jsonPath("$.failedPayments").value(1)).andExpect(jsonPath("$.revenue").value(10));
    }

    @Test
    void missingConcertAndUnauthenticatedRequestAreRejected() throws Exception {
        mvc.perform(get(path(UUID.randomUUID())).header("X-Admin-Key", KEY)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONCERT_NOT_FOUND"));
        mvc.perform(get(path(concert))).andExpect(status().isUnauthorized());
    }

    @Test
    void throughputUsesSuccessfulCompletionTimeAndExactConcertWindow() throws Exception {
        UUID other = jdbc.queryForObject("""
                insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id)
                values ('Other',now()+interval '1 day',now(),?) returning id
                """, UUID.class, UUID.randomUUID());
        throughputPayment(concert, "SUCCESS", -1);
        throughputPayment(concert, "SUCCESS", 0);
        throughputPayment(concert, "SUCCESS", -59);
        throughputPayment(concert, "SUCCESS", -60);
        throughputPayment(concert, "SUCCESS", -61);
        throughputPayment(concert, "SUCCESS", 1);
        throughputPayment(concert, "SUCCESS", null);
        throughputPayment(concert, "FAILED", -1);
        throughputPayment(concert, "PENDING", -1);
        throughputPayment(other, "SUCCESS", -1);
        mvc.perform(get(path(concert)).header("X-Admin-Key", KEY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.purchasesLastMinute").value(3))
                .andExpect(jsonPath("$.throughputWindowSeconds").value(60))
                .andExpect(jsonPath("$.purchasesPerSecond").value(0.05))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private void throughputPayment(UUID concertId, String status, Integer seconds) {
        int number = jdbc.queryForObject("select count(*) + 1 from seats", Integer.class);
        jdbc.update("""
                with seat as (
                    insert into seats(concert_id,section,seat_row,seat_number,price)
                    values (?,'RATE','1',?,10) returning id
                ), reservation as (
                    insert into reservations(user_id,seat_id,expires_at)
                    select ?,id,now()+interval '5 minutes' from seat returning id
                )
                insert into payments(reservation_id,amount,status,simulation_mode,created_at,completed_at)
                select id,10,?,'SUCCESS',now()-interval '1 day',
                    case when ?::integer is null then null else now()+(? * interval '1 second') end
                from reservation
                """, concertId, number, owner, status, seconds, seconds);
    }
    private String path(UUID id) {
        return "/api/v1/admin/concerts/" + id + "/stats";
    }
}
