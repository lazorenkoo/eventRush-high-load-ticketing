package com.lazorenkoo.eventrush.relayforge;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class BoundedResponseBody implements HttpResponse.BodySubscriber<byte[]> {
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
            delegate.onError(new IOException("RelayForge response exceeds limit"));
        } else delegate.onNext(buffers);
    }

    public void onError(Throwable error) {
        delegate.onError(error);
    }

    public void onComplete() {
        delegate.onComplete();
    }
}
