package dev.otectus.mcaconversations.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The JDK's own HTTP client against an OpenAI-compatible endpoint, as MCA's {@code OpenAIChatAI}
 * posts to it (bearer token, JSON body), but asynchronous and with a hard timeout. It runs on a small
 * pool of daemon threads of its own, so a slow endpoint occupies neither the server thread nor the
 * common pool, and cannot hold the JVM open at shutdown.
 */
public final class HttpAiTransport implements AiTransport {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, new DaemonFactory());
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(EXECUTOR)
            .build();

    @Override
    public CompletableFuture<AiHttpResult> send(String endpoint, String token, String body, Duration timeout) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(AiHttpResult.failed("invalid_endpoint"));
        }
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> AiHttpResult.parse(response.statusCode(), response.body()))
                // The request timeout bounds waiting for headers; this bounds the whole exchange.
                .orTimeout(timeout.toMillis() + 2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .exceptionally(t -> AiHttpResult.failed(isTimeout(t) ? "timeout" : "network_error"));
    }

    private static boolean isTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.util.concurrent.TimeoutException || c instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static final class DaemonFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "MCAConversations-AI-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
