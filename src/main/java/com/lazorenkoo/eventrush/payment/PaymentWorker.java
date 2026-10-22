package com.lazorenkoo.eventrush.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "eventrush.payment.worker.enabled", havingValue = "true")
public class PaymentWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentWorker.class);
    private final PaymentBatchService service;

    public PaymentWorker(PaymentBatchService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${eventrush.payment.worker.delay-ms:1000}")
    public void run() {
        try {
            service.completeDueBatch();
        } catch (RuntimeException error) {
            log.warn("Payment completion will be retried: {}", error.getClass().getSimpleName());
        }
    }
}
