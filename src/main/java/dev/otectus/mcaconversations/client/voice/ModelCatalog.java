package dev.otectus.mcaconversations.client.voice;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Asks an engine which speech models this key can use, so a model name never has to be guessed (Google
 * in particular renames its preview TTS models). Parsing is pure and tested.
 */
final class ModelCatalog {

    private ModelCatalog() {
    }

    /** Gemini models whose name says TTS and which support {@code generateContent}, best first. */
    static CompletableFuture<List<String>> gemini(String apiKey) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(GeminiSpeech.BASE.replaceAll("/$", "") + "?pageSize=1000"))
                .timeout(Http.TIMEOUT).header("x-goog-api-key", apiKey).GET().build();
        return Http.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(r -> {
            if (r.statusCode() / 100 != 2) {
                throw new Http.Failure(r.statusCode(), r.body());
            }
            return parseGemini(r.body());
        });
    }

    /** OpenAI (or compatible) models whose id says TTS. */
    static CompletableFuture<List<String>> openAi(String speechEndpoint, String apiKey) {
        String models = speechEndpoint.replaceAll("/audio/speech/?$", "/models");
        HttpRequest request = HttpRequest.newBuilder(URI.create(models))
                .timeout(Http.TIMEOUT).header("Authorization", "Bearer " + apiKey).GET().build();
        return Http.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(r -> {
            if (r.statusCode() / 100 != 2) {
                throw new Http.Failure(r.statusCode(), r.body());
            }
            return parseOpenAi(r.body());
        });
    }

    static List<String> parseGemini(String json) {
        List<String> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("models")) {
            return out;
        }
        for (JsonElement element : root.getAsJsonArray("models")) {
            JsonObject model = element.getAsJsonObject();
            String name = model.has("name") ? model.get("name").getAsString().replaceFirst("^models/", "") : "";
            boolean generates = false;
            if (model.has("supportedGenerationMethods")) {
                for (JsonElement method : model.getAsJsonArray("supportedGenerationMethods")) {
                    generates |= "generateContent".equals(method.getAsString());
                }
            }
            if (generates && name.toLowerCase(Locale.ROOT).contains("tts")) {
                out.add(name);
            }
        }
        out.sort(rank());
        return out;
    }

    static List<String> parseOpenAi(String json) {
        List<String> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("data")) {
            return out;
        }
        for (JsonElement element : root.getAsJsonArray("data")) {
            String id = element.getAsJsonObject().get("id").getAsString();
            if (id.toLowerCase(Locale.ROOT).contains("tts")) {
                out.add(id);
            }
        }
        out.sort(rank());
        return out;
    }

    /** Flash before pro (faster, cheaper for chat lines); otherwise alphabetical. */
    private static Comparator<String> rank() {
        return Comparator.comparingInt((String n) -> n.contains("flash") || n.contains("mini") ? 0 : 1)
                .thenComparing(Comparator.naturalOrder());
    }
}
