package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.admin.SeatCreationService.SeatDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class SeatCreationController {

    private final SeatCreationService service;

    public SeatCreationController(SeatCreationService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/admin/concerts/{id}/seats")
    public ResponseEntity<List<UUID>> create(@PathVariable UUID id, @Valid @RequestBody SeatBatch request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(id, request.seats()));
    }

    public record SeatBatch(@NotEmpty @Size(max = 100) List<@NotNull @Valid SeatDefinition> seats) {
    }
}
