package com.lazorenkoo.eventrush.load;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoadBuyerWorkerTest {
    private final LoadBuyerHttp transport = mock(LoadBuyerHttp.class);
    private final LoadBuyerHttp.Visit visit = mock(LoadBuyerHttp.Visit.class);
    private final LoadBuyerAdmission admission = mock(LoadBuyerAdmission.class);
    private final LoadBuyerPurchase purchase = mock(LoadBuyerPurchase.class);
    private final LoadClientResults results = mock(LoadClientResults.class);
    private final LoadBuyerWorker worker = new LoadBuyerWorker(transport, admission, purchase, results);
    private final LoadClientLeases.Client client = new LoadClientLeases.Client(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "First", "Last", UUID.randomUUID());
    private final UUID participant = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        when(transport.visit(client.id())).thenReturn(visit);
        when(admission.admit(client, visit)).thenReturn(participant);
        when(purchase.purchase(client, participant, visit)).thenReturn(LoadClientResults.Outcome.SUCCEEDED);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "sold-out", "fenced", "write-failure"})
    void closesVisitBeforeSingleResultWriteAndNeverReplaysPurchase(String mode) throws Exception {
        var outcome = mode.equals("sold-out") ? LoadClientResults.Outcome.SOLD_OUT : LoadClientResults.Outcome.SUCCEEDED;
        when(purchase.purchase(client, participant, visit)).thenReturn(outcome);
        when(results.finish(client.id(), client.leaseToken(), outcome, null)).thenAnswer(invocation -> {
            verify(visit).close();
            if (mode.equals("write-failure")) throw new IllegalStateException("private details");
            return !mode.equals("fenced");
        });
        assertThatCode(() -> worker.execute(client)).doesNotThrowAnyException();
        var order = inOrder(transport, admission, purchase, visit, results);
        order.verify(transport).visit(client.id());
        order.verify(admission).admit(client, visit);
        order.verify(purchase).purchase(client, participant, visit);
        order.verify(visit).close();
        order.verify(results).finish(client.id(), client.leaseToken(), outcome, null);
        verifyNoMoreInteractions(transport, admission, purchase, visit, results);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admission-timeout", "purchase-timeout", "admission-io", "purchase-io",
            "admission-runtime", "purchase-runtime"})
    void persistsOnlyFiniteFailureCodesAndSkipsPurchaseAfterAdmissionFailure(String mode) throws Exception {
        Exception error = mode.endsWith("timeout") ? new HttpTimeoutException("private details")
                : mode.endsWith("io") ? new IOException("private details") : new IllegalStateException("private details");
        boolean beforePurchase = mode.startsWith("admission");
        if (beforePurchase) when(admission.admit(client, visit)).thenThrow(error);
        else when(purchase.purchase(client, participant, visit)).thenThrow(error);
        var failure = mode.endsWith("io") ? LoadClientResults.Failure.HTTP_ERROR
                : mode.equals("admission-timeout") ? LoadClientResults.Failure.QUEUE_TIMEOUT
                : beforePurchase ? LoadClientResults.Failure.ADMISSION_REJECTED : LoadClientResults.Failure.PURCHASE_REJECTED;
        worker.execute(client);
        verify(results).finish(client.id(), client.leaseToken(), LoadClientResults.Outcome.FAILED, failure);
        verify(visit).close();
        if (beforePurchase) verifyNoInteractions(purchase);
        else verify(purchase).purchase(client, participant, visit);
        verifyNoMoreInteractions(results);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admission", "purchase", "write-failure"})
    void clearsInterruptForCancellationWriteAndRestoresItEvenWhenWriteFails(String stage) throws Exception {
        if (stage.equals("purchase")) when(purchase.purchase(client, participant, visit)).thenThrow(new InterruptedException());
        else when(admission.admit(client, visit)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new InterruptedException();
        });
        when(results.finish(client.id(), client.leaseToken(), LoadClientResults.Outcome.CANCELLED, null))
                .thenAnswer(invocation -> {
                    assertThat(Thread.currentThread().isInterrupted()).isFalse();
                    if (stage.equals("write-failure")) throw new IllegalStateException("private details");
                    return true;
                });
        worker.execute(client);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(results).finish(client.id(), client.leaseToken(), LoadClientResults.Outcome.CANCELLED, null);
        verify(visit).close();
        if (!stage.equals("purchase")) verifyNoInteractions(purchase);
    }
}
