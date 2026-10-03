package dev.otectus.mcaconversations.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.otectus.mcaconversations.disposition.DispositionAxis;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text a model returned into an {@link AiReply}. Pure: no world, no config, no logging.
 *
 * <p>The reply format is a superset of MCA's own {@code StructuredResponse}: {@code message} and
 * {@code optionalCommand} keep MCA's names, so an MCA-style reply is understood too. Everything the
 * model can say about consequences is parsed into closed vocabularies with bounded values here; an
 * unknown key, an unknown effect type, an out-of-range number or a malformed field is dropped on its
 * own, never failing the reply. A reply that is not structured at all still yields its line, with no
 * judgement and no effects, so a model that ignores the format talks but cannot move anything.
 */
public final class AiReplyParser {

    /** At most this many requested effects are even looked at; the rest are ignored. */
    public static final int MAX_EFFECTS = 3;

    /**
     * The axes a conversation may nudge. ATTRACTION is left out (romance has its own gated path) and
     * FAMILIARITY is earned by time spent together, not granted by a judgement.
     */
    static final Set<DispositionAxis> NUDGEABLE_AXES =
            EnumSet.of(DispositionAxis.TRUST, DispositionAxis.RESPECT, DispositionAxis.WARMTH, DispositionAxis.TENSION);

    private static final Pattern COMMAND_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");
    private static final Pattern FENCE = Pattern.compile("```[a-zA-Z]*");
    private static final Pattern MESSAGE_FIELD =
            Pattern.compile("\"(?:message|dialogue)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private AiReplyParser() {
    }

    /** The parsed reply, or empty when the model said nothing usable. */
    public static Optional<AiReply> parse(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String text = FENCE.matcher(content).replaceAll("").strip();
        int start = text.indexOf('{');
        if (start < 0) {
            return line(text);
        }
        int end = text.lastIndexOf('}');
        if (end > start) {
            JsonObject json = parseObject(text.substring(start, end + 1));
            if (json != null) {
                Optional<AiReply> structured = fromJson(json);
                if (structured.isPresent()) {
                    return structured;
                }
            }
        }
        // Broken or truncated JSON: salvage the line if the message field is recognisable, never the
        // effects, and never the raw JSON itself as something a villager says.
        Matcher m = MESSAGE_FIELD.matcher(text);
        return m.find() ? line(unescape(m.group(1))) : Optional.empty();
    }

    private static Optional<AiReply> line(String raw) {
        String dialogue = AiText.clean(raw, AiText.MAX_DIALOGUE);
        return dialogue.isEmpty() ? Optional.empty() : Optional.of(AiReply.dialogueOnly(dialogue));
    }

    private static JsonObject parseObject(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Optional<AiReply> fromJson(JsonObject json) {
        String dialogue = AiText.clean(string(json, "message").or(() -> string(json, "dialogue")).orElse(""),
                AiText.MAX_DIALOGUE);
        if (dialogue.isEmpty()) {
            return Optional.empty();
        }
        String command = string(json, "optionalCommand").or(() -> string(json, "command"))
                .map(c -> c.trim().toLowerCase(Locale.ROOT))
                .filter(c -> COMMAND_ID.matcher(c).matches())
                .orElse("");

        JsonObject assessment = json.has("assessment") && json.get("assessment").isJsonObject()
                ? json.getAsJsonObject("assessment") : json;
        AiSentiment sentiment = string(assessment, "impact").or(() -> string(assessment, "sentiment"))
                .flatMap(AiSentiment::byKey).orElse(AiSentiment.NEUTRAL);
        double confidence = number(assessment, "confidence").orElse(sentiment == AiSentiment.NEUTRAL ? 1.0 : 0.0);

        AiEmotion emotion = string(json, "emotion").flatMap(AiEmotion::byKey).orElse(AiEmotion.NEUTRAL);
        Optional<AiMemoryNote> memory = memory(json.get("memory"));

        List<AiEffect> effects = new ArrayList<>();
        if (json.has("effects") && json.get("effects").isJsonArray()) {
            JsonArray array = json.getAsJsonArray("effects");
            for (int i = 0; i < array.size() && i < MAX_EFFECTS; i++) {
                if (array.get(i).isJsonObject()) {
                    parseEffect(array.get(i).getAsJsonObject()).ifPresent(effects::add);
                }
            }
        }
        return Optional.of(new AiReply(dialogue, command, sentiment, confidence, emotion, memory, effects, true));
    }

    private static Optional<AiMemoryNote> memory(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        String text;
        AiImportance importance = AiImportance.MEDIUM;
        if (element.isJsonPrimitive()) {
            text = element.getAsString();
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            text = string(object, "text").orElse("");
            importance = string(object, "importance").flatMap(AiImportance::byKey).orElse(AiImportance.MEDIUM);
        } else {
            return Optional.empty();
        }
        String clean = AiText.clean(text, AiText.MAX_MEMORY);
        return clean.isEmpty() ? Optional.empty() : Optional.of(new AiMemoryNote(clean, importance));
    }

    /** One requested effect, typed and bounded, or empty when its type or parameters are not legal. */
    static Optional<AiEffect> parseEffect(JsonObject json) {
        String type = string(json, "type").map(t -> t.trim().toLowerCase(Locale.ROOT)).orElse("");
        if (AiEffect.DispositionNudge.TYPE.equals(type)) {
            Optional<DispositionAxis> axis = string(json, "axis").flatMap(DispositionAxis::byKey)
                    .filter(NUDGEABLE_AXES::contains);
            int direction = direction(json);
            if (axis.isPresent() && direction != 0) {
                return Optional.of(new AiEffect.DispositionNudge(axis.get(), direction));
            }
        }
        return Optional.empty();
    }

    private static int direction(JsonObject json) {
        JsonElement element = json.get("direction");
        if (element == null || !element.isJsonPrimitive()) {
            return 0;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return Integer.signum((int) Math.round(primitive.getAsDouble()));
        }
        return switch (primitive.getAsString().trim().toLowerCase(Locale.ROOT)) {
            case "up", "+", "+1", "increase", "raise" -> 1;
            case "down", "-", "-1", "decrease", "lower" -> -1;
            default -> 0;
        };
    }

    private static Optional<String> string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return Optional.empty();
        }
        return Optional.of(element.getAsString());
    }

    private static Optional<Double> number(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return Optional.empty();
        }
        try {
            double value = element.getAsJsonPrimitive().isNumber()
                    ? element.getAsDouble() : Double.parseDouble(element.getAsString().trim());
            // A percentage (85) is read as 0.85. Anything else out of range reads as no confidence:
            // a malformed confidence may only ever lower the effect of a judgement, never raise it.
            if (value > 1 && value <= 100) {
                return Optional.of(value / 100);
            }
            return Optional.of(value > 100 || value < 0 ? 0.0 : value);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String unescape(String json) {
        try {
            return JsonParser.parseString("\"" + json + "\"").getAsString();
        } catch (RuntimeException e) {
            return json.replace("\\\"", "\"").replace("\\n", " ");
        }
    }
}
