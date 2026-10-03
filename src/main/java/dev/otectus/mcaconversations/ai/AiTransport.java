package dev.otectus.mcaconversations.ai;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Sends one request. Implementations must never block the caller and must always complete. */
public interface AiTransport {

    CompletableFuture<AiHttpResult> send(String endpoint, String token, String body, Duration timeout);
}
