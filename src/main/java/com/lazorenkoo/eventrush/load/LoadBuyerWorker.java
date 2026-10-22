package com.lazorenkoo.eventrush.load;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

@Service
@ConditionalOnProperty(name = "eventrush.crowdgate.enabled", havingValue = "true")
public class LoadBuyerWorker {
    private static final Logger log = LoggerFactory.getLogger(LoadBuyerWorker.class);
    private final LoadBuyerHttp transport;
    private final LoadBuyerAdmission admission;
    private final LoadBuyerPurchase purchase;
    private final LoadClientResults results;

    public LoadBuyerWorker(LoadBuyerHttp transport, LoadBuyerAdmission admission, LoadBuyerPurchase purchase,
            LoadClientResults results) {
        this.transport = transport;
        this.admission = admission;
        this.purchase = purchase;
        this.results = results;
    }

    public void execute(LoadClientLeases.Client client) {
        LoadClientResults.Outcome outcome = LoadClientResults.Outcome.FAILED;
        LoadClientResults.Failure failure = LoadClientResults.Failure.WORKER_ERROR;
        boolean purchasing = false;
        boolean interrupted = false;
        try (var visit = transport.visit(client.id())) {
            var participant = admission.admit(client, visit);
            purchasing = true;
            outcome = purchase.purchase(client, participant, visit);
            failure = null;
        } catch (InterruptedException exception) {
            // Clear the flag only while persisting cancellation, then restore it for the executor.
            Thread.interrupted();
            interrupted = true;
            outcome = LoadClientResults.Outcome.CANCELLED;
            failure = null;
        } catch (HttpTimeoutException exception) {
            failure = purchasing ? LoadClientResults.Failure.PURCHASE_REJECTED : LoadClientResults.Failure.QUEUE_TIMEOUT;
        } catch (IOException exception) {
            failure = LoadClientResults.Failure.HTTP_ERROR;
        } catch (RuntimeException exception) {
            failure = purchasing ? LoadClientResults.Failure.PURCHASE_REJECTED
                    : LoadClientResults.Failure.ADMISSION_REJECTED;
            log.warn("Buyer stage failed: {}", exception.getClass().getSimpleName());
        }
        try {
            // The database independently proves successful payment and fences stale leases.
            if (!results.finish(client.id(), client.leaseToken(), outcome, failure)) {
                log.warn("Buyer completion was not accepted; lease recovery remains authoritative");
            }
        } catch (RuntimeException exception) {
            // Never replay a purchase after an uncertain result write. Expiry will recover the lease.
            log.warn("Buyer completion could not be stored: {}", exception.getClass().getSimpleName());
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
