package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.load.LoadRunLifecycleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@ConditionalOnProperty(name = {"eventrush.load.enabled", "eventrush.crowdgate.enabled"}, havingValue = "true")
public class LoadRunStartController {
    private final LoadRunLifecycleService lifecycle;

    public LoadRunStartController(LoadRunLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PostMapping("/api/v1/admin/concerts/{concertId}/load-runs/{runId}/start")
    public ResponseEntity<LoadRunControlController.State> start(@PathVariable UUID concertId,
            @PathVariable UUID runId) {
        String status = lifecycle.start(concertId, runId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(new LoadRunControlController.State(runId, status));
    }
}
