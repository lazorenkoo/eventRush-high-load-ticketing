package com.lazorenkoo.eventrush.admin;

import com.lazorenkoo.eventrush.admission.BrowserOriginFilter;
import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
import com.lazorenkoo.eventrush.load.LoadRunLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LoadRunStartControllerTest {
    private static final String KEY = "start-test-admin-key-1234567890123456";
    private static final String ORIGIN = "http://localhost:8082";
    private final LoadRunLifecycleService lifecycle = mock(LoadRunLifecycleService.class);
    private final UUID concert = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();
    private final String path = "/api/v1/admin/concerts/" + concert + "/load-runs/" + run + "/start";
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LoadRunStartController(lifecycle))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new BrowserOriginFilter(ORIGIN), new AdminAuthenticationFilter(KEY)).build();

    @Test
    void acceptedRequestDelegatesExactIdentityAndNeverStartsWorkersInline() throws Exception {
        when(lifecycle.start(concert, run)).thenReturn("RUNNING");
        mvc.perform(post(path).header("Origin", ORIGIN).header("X-Admin-Key", KEY))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.runId").value(run.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"));
        verify(lifecycle).start(concert, run);
        verifyNoMoreInteractions(lifecycle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-key", "wrong-key", "duplicate-key", "missing-origin", "wrong-origin", "duplicate-origin"})
    void rejectsUnauthorizedAndCrossOriginRequestsBeforeLifecycle(String mode) throws Exception {
        var request = post(path);
        if (!mode.equals("missing-key")) request.header("X-Admin-Key", mode.equals("wrong-key") ? "wrong" : KEY);
        if (mode.equals("duplicate-key")) request.header("X-Admin-Key", KEY);
        if (!mode.equals("missing-origin")) request.header("Origin", mode.equals("wrong-origin") ? "http://other" : ORIGIN);
        if (mode.equals("duplicate-origin")) request.header("Origin", ORIGIN);
        mvc.perform(request).andExpect(status().is(mode.contains("origin") ? 403 : 401))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(lifecycle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOAD_RUN_NOT_FOUND", "LOAD_RUN_ALREADY_ACTIVE"})
    void preservesSanitizedLifecycleRejections(String code) throws Exception {
        boolean missing = code.equals("LOAD_RUN_NOT_FOUND");
        when(lifecycle.start(concert, run)).thenThrow(new ResponseStatusException(
                missing ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT, code));
        mvc.perform(post(path).header("Origin", ORIGIN).header("X-Admin-Key", KEY))
                .andExpect(status().is(missing ? 404 : 409)).andExpect(jsonPath("$.code").value(code))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(lifecycle).start(concert, run);
    }

    @Test
    void wrongMethodAndMalformedIdentityCannotStartRun() throws Exception {
        mvc.perform(get(path).header("X-Admin-Key", KEY)).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(path.replace(run.toString(), "invalid")).header("Origin", ORIGIN)
                .header("X-Admin-Key", KEY)).andExpect(status().isBadRequest());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void controllerRequiresBothExplicitFeatureFlags() {
        for (boolean load : new boolean[] {false, true}) {
            for (boolean gate : new boolean[] {false, true}) {
                try (var context = new AnnotationConfigApplicationContext()) {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("flags", Map.of(
                            "eventrush.load.enabled", load, "eventrush.crowdgate.enabled", gate)));
                    context.registerBean(LoadRunLifecycleService.class, () -> lifecycle);
                    context.register(LoadRunStartController.class);
                    context.refresh();
                    assertThat(context.getBeansOfType(LoadRunStartController.class)).hasSize(load && gate ? 1 : 0);
                }
            }
        }
        verifyNoInteractions(lifecycle);
    }
}
