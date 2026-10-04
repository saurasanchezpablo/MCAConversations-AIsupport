package dev.otectus.mcaconversations.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.mca.McaChatAi;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Rewrites a villager's line that promised something the game could not do, so what they say matches
 * what happens: same voice, mood and language, but true. One short request, with a tight timeout; the
 * caller falls back to a scripted, truthful line if it fails.
 */
final class AiRepair {

    static final int TIMEOUT_SECONDS = 12;

    private AiRepair() {
    }

    /** The request body. Pure. */
    static String body(String model, boolean jsonMode, String villagerName, String playerName, String language,
                       String original, List<String> truths) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are ").append(villagerName).append(", a villager in a Minecraft village, talking with ")
                .append(playerName).append(". You were about to say: \"").append(original).append("\"\n")
                .append("But this is what is actually true in the game right now:\n");
        truths.forEach(t -> prompt.append("- ").append(t).append('\n'));
        prompt.append("Say it again so it matches what you can and cannot do. Keep your voice, your mood and the ")
                .append(language == null || language.isBlank() ? "same language" : language)
                .append(". One or two short sentences. Never say you are doing or will do something you cannot. ")
                .append("If you need something from ").append(playerName).append(", ask for it plainly.\n")
                .append("Reply with exactly one JSON object: {\"message\": \"what you say\"}");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", prompt.toString());
        messages.add(user);
        body.add("messages", messages);
        if (jsonMode) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        }
        return body.toString();
    }

    /** The rewritten line, or empty when the endpoint failed or answered nonsense. Never blocks. */
    static CompletableFuture<Optional<String>> rewrite(McaChatAi.Settings settings, String villagerName, String playerName,
                                                        String language, String original, List<String> truths) {
        String body = body(settings.model(), McaConversationsConfig.aiRequestJsonMode(), villagerName, playerName,
                language, original, truths);
        return AiConversations.transport().send(settings.endpoint(), settings.tokenFor(playerName), body,
                        Duration.ofSeconds(TIMEOUT_SECONDS))
                .exceptionally(t -> AiHttpResult.failed("network_error"))
                .thenApply(result -> result.content().flatMap(AiReplyParser::parse).map(AiReply::dialogue)
                        .map(text -> AiText.clean(text, AiText.MAX_DIALOGUE)).filter(text -> !text.isBlank()));
    }
}
