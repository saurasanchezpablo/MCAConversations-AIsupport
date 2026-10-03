package dev.otectus.mcaconversations.client.voice;

import com.google.gson.JsonObject;
import dev.otectus.mcaconversations.voice.VoiceDirection;
import dev.otectus.mcaconversations.voice.VoiceScript;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI's speech endpoint. {@code gpt-4o-mini-tts} takes free-form {@code instructions}, which is
 * where the acting brief goes; {@code response_format: pcm} returns 24 kHz signed 16-bit mono, which
 * plays without any decoding.
 */
final class OpenAiSpeech implements SpeechEngine {

    static final int SAMPLE_RATE = 24_000;

    private final String endpoint;
    private final String apiKey;
    private final String model;

    OpenAiSpeech(String endpoint, String apiKey, String model) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
    }

    static String body(String model, String text, String voice, VoiceDirection direction) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", text);
        body.addProperty("voice", voice);
        body.addProperty("instructions", VoiceScript.instructions(direction));
        body.addProperty("response_format", "pcm");
        return body.toString();
    }

    @Override
    public CompletableFuture<Pcm> speak(String text, String voice, VoiceDirection direction) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Http.TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(model, text, voice, direction), StandardCharsets.UTF_8))
                .build();
        return Http.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
            if (response.statusCode() / 100 != 2) {
                throw new Http.Failure(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
            }
            return new Pcm(response.body(), SAMPLE_RATE);
        });
    }
}
