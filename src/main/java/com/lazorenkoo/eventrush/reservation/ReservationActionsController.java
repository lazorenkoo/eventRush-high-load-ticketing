package com.lazorenkoo.eventrush.reservation;

import com.lazorenkoo.eventrush.admission.AdmissionSessionStore;
import com.lazorenkoo.eventrush.admission.SessionCookie;
import com.lazorenkoo.eventrush.payment.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reservations/{id}")
@ConditionalOnProperty(name = "eventrush.payment.worker.enabled", havingValue = "true")
public class ReservationActionsController {

    private final AdmissionSessionStore sessions;
    private final SessionCookie cookies;
    private final PaymentService payments;
    private final ReservationCancellationService cancellations;

    public ReservationActionsController(AdmissionSessionStore sessions, SessionCookie cookies,
            PaymentService payments, ReservationCancellationService cancellations) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.payments = payments;
        this.cancellations = cancellations;
    }

    @PostMapping("/pay")
    public ResponseEntity<PaymentService.Payment> pay(@PathVariable UUID id,
            @Valid @RequestBody PaymentRequest body, HttpServletRequest request) {
        UUID owner = owner(request);
        var payment = payments.start(id, owner, body.mode());
        HttpStatus status = "PENDING".equals(payment.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(payment);
    }

    @PostMapping("/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id, HttpServletRequest request) {
        cancellations.cancel(id, owner(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private UUID owner(HttpServletRequest request) {
        return sessions.require(cookies.require(request)).admission().visitorId();
    }

    public record PaymentRequest(@NotNull PaymentService.Mode mode) {
    }
}
