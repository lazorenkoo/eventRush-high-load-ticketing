package com.lazorenkoo.eventrush.downstream;

import com.lazorenkoo.eventrush.api.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WebhookControllerTest {

    private final DownstreamProcessor processor = mock(DownstreamProcessor.class);
    private MockMvc mvc;
    private static final String BODY = "{\"_eventrush\":{\"id\":\"00000000-0000-0000-0000-000000000071\","
            + "\"type\":\"ticket.purchased\",\"version\":1}}";

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new WebhookController(processor, key("tickets"),
                key("accounting"), key("notifications"))).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tickets", "accounting", "notifications"})
    void eachRecipientAcceptsOnlyItsOwnSignature(String recipient) throws Exception {
        mvc.perform(signed(recipient, BODY, key(recipient))).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(processor).process(eq(DownstreamProcessor.Consumer.valueOf(recipient.toUpperCase())),
                argThat(event -> event.type().equals("ticket.purchased") && event.payload().equals(BODY)));
    }

    @Test
    void signatureForAnotherRecipientIsRejected() throws Exception {
        mvc.perform(signed("accounting", BODY, key("tickets"))).andExpect(status().isUnauthorized());
        verifyNoInteractions(processor);
    }

    @Test
    void missingSignatureIsRejectedBeforeJsonParsing() throws Exception {
        mvc.perform(post("/webhooks/tickets").contentType("application/json").content("invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_WEBHOOK_SIGNATURE"));
        verifyNoInteractions(processor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Webhook-Timestamp", "X-Webhook-Signature"})
    void repeatedAuthenticationHeadersAreRejected(String header) throws Exception {
        mvc.perform(signed("tickets", BODY, key("tickets")).header(header, "duplicate"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(processor);
    }

    @Test
    void validSignatureDoesNotMakeMalformedOrLegacyJsonAcceptable() throws Exception {
        mvc.perform(signed("tickets", "{}", key("tickets"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_WEBHOOK_EVENT"));
        verifyNoInteractions(processor);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void oversizedBodiesAreRejectedWithOrWithoutContentLength(boolean declaredLength) throws Exception {
        var request = signed("tickets", "a".repeat(65_537), key("tickets"));
        if (!declaredLength) {
            request.with(original -> {
                var streaming = spy(original);
                doReturn(-1L).when(streaming).getContentLengthLong();
                return streaming;
            });
        }
        mvc.perform(request).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(processor);
    }

    @Test
    void unknownRecipientAndWrongContentTypeAreRejected() throws Exception {
        mvc.perform(signed("unknown", BODY, key("tickets"))).andExpect(status().isNotFound());
        mvc.perform(post("/webhooks/tickets").contentType("text/plain").content(BODY))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(processor);
    }

    private MockHttpServletRequestBuilder signed(String recipient, String body, String secret) throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "sha256=" + HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        return post("/webhooks/" + recipient).contentType("application/json").content(body)
                .header("X-Webhook-Timestamp", timestamp).header("X-Webhook-Signature", signature);
    }

    private String key(String recipient) {
        return recipient + "-test-only-signing-secret-at-least-32-characters";
    }
}
