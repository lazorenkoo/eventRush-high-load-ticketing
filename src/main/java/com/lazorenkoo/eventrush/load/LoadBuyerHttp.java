package com.lazorenkoo.eventrush.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class LoadBuyerHttp {
    private final ObjectMapper mapper;
    private final URI base;
    private final String origin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public LoadBuyerHttp(ObjectMapper mapper, @Value("${eventrush.load.base-url:http://localhost:8082}") URI base,
            @Value("${eventrush.browser.origin:http://localhost:8082}") String origin) {
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
            throw new IllegalArgumentException("Load target must be an HTTP origin");
        }
        this.mapper = mapper;
        this.base = base.resolve("/");
        this.origin = origin;
    }

    public Visit visit(UUID visitor) {
        return new Visit(java.util.Objects.requireNonNull(visitor, "visitor"));
    }

    @PreDestroy
    public void close() {
        http.close();
    }

    public final class Visit implements AutoCloseable {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);

        private Visit(UUID visitor) {
            var cookie = new HttpCookie("cg_user_id", visitor.toString());
            cookie.setVersion(0);
            cookie.setPath("/");
            cookies.getCookieStore().add(base, cookie);
        }

        public HttpResponse<byte[]> send(String method, String path, Object payload)
                throws IOException, InterruptedException {
            URI relative = URI.create(path);
            if (!Set.of("GET", "POST", "DELETE").contains(method)
                    || !relative.getRawPath().matches("/api/v1/[A-Za-z0-9:/_-]+")
                    || relative.isAbsolute() || relative.getRawAuthority() != null || relative.getFragment() != null) {
                throw new IllegalArgumentException("Invalid load request");
            }
            URI target = base.resolve(relative);
            var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5))
                    .header("Origin", origin).header("Accept", "application/json");
            cookies.get(target, java.util.Map.of()).forEach((name, values) ->
                    values.forEach(value -> request.header(name, value)));
            request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload)));
            if (payload != null) request.header("Content-Type", "application/json");
            var pending = http.sendAsync(request.build(), ignored -> new LimitedBody());
            try {
                var response = pending.get(5, TimeUnit.SECONDS);
                cookies.put(target, response.headers().map());
                return response;
            } catch (TimeoutException exception) {
                pending.cancel(true);
                throw new HttpTimeoutException("Buyer request timed out");
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof IOException error) throw error;
                throw new IOException("Buyer request failed", exception.getCause());
            } catch (InterruptedException exception) {
                pending.cancel(true);
                throw exception;
            }
        }

        @Override
        public void close() {
            cookies.getCookieStore().removeAll();
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;

        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) size += buffer.remaining();
            if (size > 65536) {
                subscription.cancel();
                delegate.onError(new IOException("Load response exceeds limit"));
            } else delegate.onNext(buffers);
        }

        public void onError(Throwable error) {
            delegate.onError(error);
        }

        public void onComplete() {
            delegate.onComplete();
        }
    }
}
