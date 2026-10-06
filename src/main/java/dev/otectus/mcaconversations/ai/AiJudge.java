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
 * Reads one finished exchange, the player's message and the villager's answer, and reports what the
 * player asked the villager to do and what the villager said to it ({@link AiUnderstanding}). The model
 * reads the meaning, so a request works in any language and any wording; no phrase list is involved.
 *
 * <p>Used when a reply left that reading out (or always, by {@code ai.actionJudge}). One short request
 * with a tight timeout, on the transport's threads; if it fails, the caller carries on without it.
 */
final class AiJudge {

    static final int TIMEOUT_SECONDS = 10;
    /** Earlier lines shown for context ("yes, do that" after the villager offered something). */
    static final int CONTEXT_LINES = 4;

    /** Everything the judge sees: captured on the server thread, immutable. */
    record Exchange(String villagerName, String playerName, List<AiSessions.Line> transcript, String message,
                    String line, List<String> actionOffers) {
        Exchange {
            transcript = transcript == null ? List.of() : List.copyOf(transcript);
            actionOffers = actionOffers == null ? List.of() : List.copyOf(actionOffers);
        }
    }

    private AiJudge() {
    }

    /** Whether this reply should be read again, under the server's setting. */
    static boolean wanted(AiReply reply, boolean actionsOffered) {
        if (!actionsOffered) {
            return false;
        }
        return switch (McaConversationsConfig.aiActionJudge()) {
            case OFF -> false;
            case ALWAYS -> true;
            case WHEN_MISSING -> reply.understanding().isEmpty();
        };
    }

    /** The request body. Pure. */
    static String body(String model, boolean jsonMode, Exchange x) {
        StringBuilder prompt = new StringBuilder(2048);
        prompt.append("You read one moment of a conversation in a Minecraft village and report what it means for ")
                .append("the game. You do not write dialogue. The conversation may be in any language; read the meaning, ")
                .append("not particular words.\n\n");
        prompt.append("The villager is ").append(x.villagerName()).append("; the player is ").append(x.playerName())
                .append(".\n");
        List<AiSessions.Line> recent = x.transcript().size() > CONTEXT_LINES
                ? x.transcript().subList(x.transcript().size() - CONTEXT_LINES, x.transcript().size()) : x.transcript();
        if (!recent.isEmpty()) {
            prompt.append("Earlier:\n");
            for (AiSessions.Line l : recent) {
                prompt.append("- ").append(l.fromPlayer() ? x.playerName() : x.villagerName()).append(": \"")
                        .append(l.text()).append("\"\n");
            }
        }
        prompt.append(x.playerName()).append(" now says: \"").append(x.message()).append("\"\n");
        prompt.append(x.villagerName()).append(" answers: \"").append(x.line()).append("\"\n\n");
        prompt.append("What ").append(x.villagerName()).append(" could do for ").append(x.playerName())
                .append(" right now (the only actions there are):\n");
        for (String offer : x.actionOffers()) {
            prompt.append("- ").append(offer).append('\n');
        }
        prompt.append("\nReply with exactly one JSON object and nothing else:\n")
                .append("{\"request\": null or {\"do\": \"one action name from the list\", ...the details that action ")
                .append("takes (task, amount, item, place, build, helpers)..., \"answer\": \"yes|no|later\"},\n")
                .append(" \"received\": null, or the minecraft:item_id (or \"something\") that ").append(x.villagerName())
                .append("'s answer says ").append(x.playerName()).append(" has just given or lent them}\n");
        prompt.append("Rules:\n")
                .append("- request: what ").append(x.playerName()).append("'s message asks ").append(x.villagerName())
                .append(" to do, if one of the actions would carry it out. A question, a hint, a polite wish, handing ")
                .append("something over, asking for something back, or a yes to something the villager offered earlier ")
                .append("all count. Asking for something the villager carries, or for something lent back, is give with ")
                .append("that item; wanting to hand the villager something is gift. Small talk about work is not a request. ")
                .append("null when nothing is asked.\n")
                .append("- answer: yes when the villager agrees to do it now; no when they refuse, say they cannot, or ")
                .append("dodge it; later when they agree for another time.\n")
                .append("- received: only when the villager's answer thanks them for, or mentions, something just given ")
                .append("or lent; otherwise null.\n");
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

    /** The reading, or empty when the endpoint failed or answered something unusable. Never blocks. */
    static CompletableFuture<Optional<AiUnderstanding>> read(McaChatAi.Settings settings, Exchange exchange) {
        String body = body(settings.model(), McaConversationsConfig.aiRequestJsonMode(), exchange);
        return AiConversations.transport().send(settings.endpoint(), settings.tokenFor(exchange.playerName()), body,
                        Duration.ofSeconds(TIMEOUT_SECONDS))
                .exceptionally(t -> AiHttpResult.failed("network_error"))
                .thenApply(result -> result.content().flatMap(AiReplyParser::parseUnderstanding));
    }
}
