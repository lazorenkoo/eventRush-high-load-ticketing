package com.lazorenkoo.eventrush.load;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoadDispatcherTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LoadClientLeases leases = mock(LoadClientLeases.class);
    private final LoadBuyerWorker worker = mock(LoadBuyerWorker.class);
    private final UUID run = UUID.randomUUID();
    private final AtomicReference<String> state = new AtomicReference<>("RUNNING");
    private final LoadDispatcher dispatcher = new LoadDispatcher(jdbc, leases, worker);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        var row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(run);
        when(row.getString("status")).thenAnswer(invocation -> state.get());
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation ->
                List.of(((RowMapper<?>) invocation.getArgument(1)).mapRow(row, 0)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentTicksStayBoundedAndCancellationRetainsSlotsUntilActualExit(boolean shutdown) throws Exception {
        var clients = new ArrayList<LoadClientLeases.Client>();
        for (int index = 0; index < 100; index++) {
            clients.add(new LoadClientLeases.Client(UUID.randomUUID(), run, UUID.randomUUID(), UUID.randomUUID(),
                    "First", "Last", UUID.randomUUID()));
        }
        when(leases.claim(run, 100)).thenReturn(clients);
        var entered = new CountDownLatch(100);
        var interrupted = new CountDownLatch(100);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(100);
        doAnswer(invocation -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                release.await();
            } finally {
                exited.countDown();
            }
            return null;
        }).when(worker).execute(any());
        try (var callers = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var calls = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 8; index++) calls.add(callers.submit(() -> {
                start.await();
                dispatcher.tick();
                return null;
            }));
            start.countDown();
            for (var call : calls) call.get(5, TimeUnit.SECONDS);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            verify(leases, times(1)).claim(run, 100);
            if (shutdown) dispatcher.close();
            else {
                state.set("STOPPING");
                dispatcher.tick();
            }
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            state.set("RUNNING");
            dispatcher.tick();
            verify(leases, times(1)).claim(run, 100);
            verify(worker, times(100)).execute(any());
        } finally {
            release.countDown();
            assertThat(exited.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.close();
        }
        clearInvocations(jdbc, leases, worker);
        dispatcher.tick();
        verifyNoInteractions(jdbc, leases, worker);
    }

    @Test
    void databaseFailureDoesNotKillDispatcherOrStartUnleasedClients() {
        doThrow(new IllegalStateException("private details")).doNothing().when(leases).reap(run);
        try {
            assertThatCode(dispatcher::tick).doesNotThrowAnyException();
            verify(leases, never()).claim(any(), anyInt());
            dispatcher.tick();
            verify(leases).claim(run, 100);
            verifyNoInteractions(worker);
        } finally {
            dispatcher.close();
        }
    }
}
