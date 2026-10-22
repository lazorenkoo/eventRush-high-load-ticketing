package com.lazorenkoo.eventrush.relayforge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "eventrush.relayforge.enabled", havingValue = "true")
public class OutgoingEventWorker {

    private static final Logger log = LoggerFactory.getLogger(OutgoingEventWorker.class);
    private final OutgoingEventPublisher publisher;

    public OutgoingEventWorker(OutgoingEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${eventrush.relayforge.worker.delay-ms:1000}")
    public void run() {
        try {
            for (int i = 0; i < 20 && !Thread.currentThread().isInterrupted(); i++) {
                if (!publisher.publishNext()) {
                    break;
                }
            }
        } catch (RuntimeException error) {
            log.warn("Outgoing event publishing will be retried: {}", error.getClass().getSimpleName());
        }
    }
}
