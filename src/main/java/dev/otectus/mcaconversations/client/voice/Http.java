package dev.otectus.mcaconversations.client.voice;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** The voice engines' shared HTTP client, on daemon threads of its own (never the render thread). */
final class Http {

    static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "MCAConversations-Voice-" + Counter.NEXT.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .executor(EXECUTOR).build();
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private Http() {
    }

    private static final class Counter {
        static final AtomicInteger NEXT = new AtomicInteger();
    }

    /** Thrown for a non-2xx answer, carrying the status so a rejected key can be told apart. */
    static final class Failure extends RuntimeException {
        final int status;

        Failure(int status, String body) {
            super("HTTP " + status + (body == null ? "" : ": " + body.substring(0, Math.min(200, body.length()))));
            this.status = status;
        }
    }
}
