package com.lazorenkoo.eventrush.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "eventrush.expiration.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpirationWorker {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationWorker.class);
    private final ReservationExpirationService service;

    public ReservationExpirationWorker(ReservationExpirationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${eventrush.expiration.delay-ms:5000}")
    public void run() {
        try {
            service.expireBatch();
        } catch (RuntimeException error) {
            log.warn("Reservation expiry will be retried: {}", error.getClass().getSimpleName());
        }
    }
}
