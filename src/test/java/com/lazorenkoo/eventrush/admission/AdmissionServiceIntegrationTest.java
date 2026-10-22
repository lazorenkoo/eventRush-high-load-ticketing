package com.lazorenkoo.eventrush.admission;

import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17.11:///eventrush_admission_exchange_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test", "spring.datasource.password=test", "eventrush.expiration.enabled=false"
})
@Import(AdmissionServiceIntegrationTest.Config.class)
class AdmissionServiceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdmissionService service;
    @Autowired
    private AdmissionSessionStore sessions;
    @Autowired
    private CrowdGateClient crowdGate;

    private final UUID gate = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final CrowdGateClient.Admission admission =
            new CrowdGateClient.Admission(gate, owner, UUID.randomUUID(), gate + ":" + "a".repeat(43));
    private UUID concert;

    @BeforeEach
    void createConcert() {
        reset(crowdGate);
        concert = jdbc.queryForObject("""
                insert into concerts (name, starts_at, sale_starts_at, status, crowdgate_gate_id)
                values ('Exchange show', now() + interval '1 day', now(), 'ON_SALE', ?) returning id
                """, UUID.class, gate);
        when(crowdGate.redeem(gate, owner, "signed-token")).thenReturn(admission);
        when(crowdGate.validateSession(admission)).thenReturn(Instant.now().plusSeconds(60));
    }

    @AfterEach
    void removeFixtures() {
        jdbc.update("delete from admission_sessions where gate_id = ?", gate);
        jdbc.update("delete from concerts where id = ?", concert);
    }

    @Test
    void exchangeValidatesBeforePersistingAndActiveOwnerIsRevalidated() {
        String token = service.exchange(concert, owner, "signed-token");
        var order = inOrder(crowdGate);
        order.verify(crowdGate).redeem(gate, owner, "signed-token");
        order.verify(crowdGate).validateSession(admission);
        assertThat(sessions.require(token).admission()).isEqualTo(admission);
        assertThat(service.requireActiveOwner(token, concert)).isEqualTo(owner);
        verify(crowdGate, times(2)).validateSession(admission);
        when(crowdGate.validateSession(admission)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> service.requireActiveOwner(token, concert)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectedRedemptionOrLiveValidationNeverCreatesApplicationSession() {
        when(crowdGate.redeem(gate, owner, "signed-token"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.exchange(concert, owner, "signed-token"))
                .isInstanceOf(ResponseStatusException.class);
        verify(crowdGate, never()).validateSession(any());
        doReturn(admission).when(crowdGate).redeem(gate, owner, "signed-token");
        when(crowdGate.validateSession(admission)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> service.exchange(concert, owner, "signed-token"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("select count(*) from admission_sessions where gate_id = ?",
                Integer.class, gate)).isZero();
    }

    @Test
    void missingAndClosedConcertsAreRejectedBeforeCallingCrowdGate() {
        assertThatThrownBy(() -> service.exchange(UUID.randomUUID(), owner, "signed-token"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        jdbc.update("update concerts set status = 'CANCELLED' where id = ?", concert);
        assertThatThrownBy(() -> service.exchange(concert, owner, "signed-token"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        verifyNoInteractions(crowdGate);
    }

    @TestConfiguration
    static class Config {
        @Bean
        CrowdGateClient crowdGate() {
            return mock(CrowdGateClient.class);
        }

        @Bean
        AdmissionService admissionService(JdbcTemplate jdbc, CrowdGateClient client, AdmissionSessionStore store) {
            return new AdmissionService(jdbc, client, store);
        }
    }
}
