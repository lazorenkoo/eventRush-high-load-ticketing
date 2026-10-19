package com.lazorenkoo.eventrush.concert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "eventrush.concert.sold-out-worker.enabled", havingValue = "true")
public class ConcertSoldOutWorker {

    private static final Logger log = LoggerFactory.getLogger(ConcertSoldOutWorker.class);
    private final ConcertSoldOutService service;

    public ConcertSoldOutWorker(ConcertSoldOutService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${eventrush.concert.sold-out-worker.delay-ms:1000}")
    public void run() {
        try {
            service.markSoldOutBatch();
        } catch (RuntimeException error) {
            log.warn("Sold-out detection will be retried: {}", error.getClass().getSimpleName());
        }
    }
}
