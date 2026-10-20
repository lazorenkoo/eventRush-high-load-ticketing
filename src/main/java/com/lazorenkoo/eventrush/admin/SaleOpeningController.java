package com.lazorenkoo.eventrush.admin;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class SaleOpeningController {

    private final SaleOpeningService service;

    public SaleOpeningController(SaleOpeningService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/admin/concerts/{id}/open")
    public ResponseEntity<Void> open(@PathVariable UUID id) {
        service.open(id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
