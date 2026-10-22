package com.lazorenkoo.eventrush.concert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test"
})
@Transactional
class ConcertSeatSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void duplicateSeatPositionIsRejectedWithinTheSameConcert() {
        UUID concert = concert(UUID.randomUUID());
        seat(concert, 1, BigDecimal.TEN);
        assertThatThrownBy(() -> seat(concert, 1, BigDecimal.TEN))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("seats_position_unique");
    }

    @Test
    void differentConcertsCanUseTheSameSeatPosition() {
        seat(concert(UUID.randomUUID()), 1, BigDecimal.TEN);
        seat(concert(UUID.randomUUID()), 1, BigDecimal.TEN);
        assertThat(jdbc.queryForObject("select count(*) from seats", Integer.class)).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "-1, 1", "10, 0", "10, -1"})
    void rejectsNonpositivePriceOrSeatNumber(BigDecimal price, int number) {
        UUID concert = concert(UUID.randomUUID());
        assertThatThrownBy(() -> seat(concert, number, price))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void seatMustBelongToAnExistingConcert() {
        assertThatThrownBy(() -> seat(UUID.randomUUID(), 1, BigDecimal.TEN))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("seats_concert_id_fkey");
    }

    @Test
    void oneGateCannotBeAssignedToTwoConcerts() {
        UUID gate = UUID.randomUUID();
        concert(gate);
        assertThatThrownBy(() -> concert(gate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("concerts_crowdgate_gate_id_key");
    }

    private UUID concert(UUID gate) {
        return jdbc.queryForObject("""
                insert into concerts (name, starts_at, sale_starts_at, crowdgate_gate_id)
                values ('Live show', current_timestamp + interval '1 day', current_timestamp, ?)
                returning id
                """, UUID.class, gate);
    }

    private void seat(UUID concert, int number, BigDecimal price) {
        jdbc.update("""
                insert into seats (concert_id, section, seat_row, seat_number, price)
                values (?, 'A', '1', ?, ?)
                """, concert, number, price);
    }
}
