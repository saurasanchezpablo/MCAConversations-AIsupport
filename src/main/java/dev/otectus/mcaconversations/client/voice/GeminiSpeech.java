package dev.otectus.mcaconversations.client.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcaconversations.voice.VoiceDirection;
import dev.otectus.mcaconversations.voice.VoiceScript;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini TTS through {@code generateContent} with an AUDIO response. Gemini takes direction inside
 * the prompt, so the acting brief precedes the line; the audio comes back as base64 signed 16-bit
 * mono PCM (24 kHz, read from the mime type when given).
 */
final class GeminiSpeech implements SpeechEngine {

    static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final Pattern RATE = Pattern.compile("rate=(\\d+)");

    private final String apiKey;
    private final String model;

    GeminiSpeech(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    static String body(String text, String voice, VoiceDirection direction) {
        JsonObject part = new JsonObject();
        part.addProperty("text", VoiceScript.stylePrompt(direction) + "\n" + text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject prebuilt = new JsonObject();
        prebuilt.addProperty("voiceName", voice);
        JsonObject voiceConfig = new JsonObject();
        voiceConfig.add("prebuiltVoiceConfig", prebuilt);
        JsonObject speechConfig = new JsonObject();
        speechConfig.add("voiceConfig", voiceConfig);
        JsonArray modalities = new JsonArray();
        modalities.add("AUDIO");
        JsonObject generation = new JsonObject();
        generation.add("responseModalities", modalities);
        generation.add("speechConfig", speechConfig);

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", generation);
        return body.toString();
    }

    static Pcm parse(String json) {
        JsonObject inline = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("candidates").get(0)
                .getAsJsonObject().getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject()
                .getAsJsonObject("inlineData");
        int rate = 24_000;
        if (inline.has("mimeType")) {
            Matcher m = RATE.matcher(inline.get("mimeType").getAsString());
            if (m.find()) {
                rate = Integer.parseInt(m.group(1));
            }
        }
        return new Pcm(Base64.getDecoder().decode(inline.get("data").getAsString()), rate);
    }

    @Override
    public CompletableFuture<Pcm> speak(String text, String voice, VoiceDirection direction) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + URLEncoder.encode(model, StandardCharsets.UTF_8)
                        + ":generateContent"))
                .timeout(Http.TIMEOUT)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(text, voice, direction), StandardCharsets.UTF_8))
                .build();
        return Http.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
            if (response.statusCode() / 100 != 2) {
                throw new Http.Failure(response.statusCode(), response.body());
            }
            return parse(response.body());
        });
    }
}
