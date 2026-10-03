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
    public static final int MAX_EFFECTS = 4;
    /** Longest neighbour name, quest id, topic or place token accepted. */
    static final int MAX_TOKEN = 64;
    /** Bounds the game puts on promises and wishes, whatever the model asks. */
    /** MCA hands over one item per gift, so a promise of many items means many gifts: keep it human-sized. */
    static final int MAX_PROMISE_COUNT = 8;
    static final int MAX_PROMISE_DAYS = 7;
    static final int MAX_WISH_DAYS = 10;
    /** Opinion axes a conversation may move between neighbours (SocialOpinionRecord's vocabulary). */
    static final Set<String> OPINION_AXES = Set.of("warmth", "trust", "respect");

    /**
     * The axes a reply may name. FAMILIARITY is earned by time spent together, never granted by a
     * judgement. ATTRACTION parses, but {@link AiOutcomePlan} drops it unless romance is allowed.
     */
    static final Set<DispositionAxis> NUDGEABLE_AXES = EnumSet.of(DispositionAxis.TRUST, DispositionAxis.RESPECT,
            DispositionAxis.WARMTH, DispositionAxis.TENSION, DispositionAxis.ATTRACTION);

    private static final Pattern COMMAND_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");
    /** An item id or item tag, namespace optional ({@code wheat}, {@code minecraft:wheat}, {@code #minecraft:logs}). */
    private static final Pattern ITEM_REF = Pattern.compile("#?([a-z0-9_.-]+:)?[a-z0-9_./-]+");
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_.:-]+");
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
        Optional<AiInterjection> interjection = interjection(json.get("interjection"));
        Optional<AiDelivery> delivery = delivery(json.get("delivery"));

        List<AiEffect> effects = new ArrayList<>();
        if (json.has("effects") && json.get("effects").isJsonArray()) {
            JsonArray array = json.getAsJsonArray("effects");
            for (int i = 0; i < array.size() && i < MAX_EFFECTS; i++) {
                if (array.get(i).isJsonObject()) {
                    parseEffect(array.get(i).getAsJsonObject()).ifPresent(effects::add);
                }
            }
        }
        return Optional.of(new AiReply(dialogue, command, sentiment, confidence, emotion, memory, effects,
                interjection, delivery, true));
    }

    private static Optional<AiMemoryNote> memory(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        String text;
        AiImportance importance = AiImportance.MEDIUM;
        boolean secret = false;
        if (element.isJsonPrimitive()) {
            text = element.getAsString();
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            text = string(object, "text").orElse("");
            importance = string(object, "importance").flatMap(AiImportance::byKey).orElse(AiImportance.MEDIUM);
            secret = bool(object, "secret");
        } else {
            return Optional.empty();
        }
        String clean = AiText.clean(text, AiText.MAX_MEMORY);
        return clean.isEmpty() ? Optional.empty() : Optional.of(new AiMemoryNote(clean, importance, secret));
    }

    /** The voice delivery; any field may be missing or wrong and falls back on its own. */
    static Optional<AiDelivery> delivery(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject object = element.getAsJsonObject();
        dev.otectus.mcaconversations.voice.VoiceIntent intent = string(object, "intent")
                .flatMap(dev.otectus.mcaconversations.voice.VoiceIntent::byKey)
                .orElse(dev.otectus.mcaconversations.voice.VoiceIntent.STATEMENT);
        String tone = AiText.clean(string(object, "tone").orElse(""), 80);
        dev.otectus.mcaconversations.voice.VoiceDirection.Pace pace = switch (string(object, "pace").orElse("").trim().toLowerCase(Locale.ROOT)) {
            case "slow" -> dev.otectus.mcaconversations.voice.VoiceDirection.Pace.SLOW;
            case "fast", "quick" -> dev.otectus.mcaconversations.voice.VoiceDirection.Pace.FAST;
            default -> dev.otectus.mcaconversations.voice.VoiceDirection.Pace.NORMAL;
        };
        dev.otectus.mcaconversations.voice.VoiceDirection.Volume volume = switch (string(object, "volume").orElse("").trim().toLowerCase(Locale.ROOT)) {
            case "whisper", "quiet", "soft" -> dev.otectus.mcaconversations.voice.VoiceDirection.Volume.WHISPER;
            case "raised", "loud", "shout" -> dev.otectus.mcaconversations.voice.VoiceDirection.Volume.RAISED;
            default -> dev.otectus.mcaconversations.voice.VoiceDirection.Volume.NORMAL;
        };
        float intensity = number(object, "intensity").map(Double::floatValue).orElse(0.5f);
        return Optional.of(new AiDelivery(intent, tone, pace, Math.max(0f, Math.min(1f, intensity)), volume));
    }

    private static Optional<AiInterjection> interjection(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject object = element.getAsJsonObject();
        String speaker = AiText.clean(string(object, "speaker").orElse(""), MAX_TOKEN);
        String message = AiText.clean(string(object, "message").orElse(""), AiText.MAX_DIALOGUE / 2);
        return speaker.isEmpty() || message.isEmpty() ? Optional.empty()
                : Optional.of(new AiInterjection(speaker, message));
    }

    /** One requested effect, typed and bounded, or empty when its type or parameters are not legal. */
    static Optional<AiEffect> parseEffect(JsonObject json) {
        String type = string(json, "type").map(t -> t.trim().toLowerCase(Locale.ROOT)).orElse("");
        switch (type) {
            case AiEffect.DispositionNudge.TYPE -> {
                Optional<DispositionAxis> axis = string(json, "axis").flatMap(DispositionAxis::byKey)
                        .filter(NUDGEABLE_AXES::contains);
                int direction = direction(json);
                if (axis.isPresent() && direction != 0) {
                    return Optional.of(new AiEffect.DispositionNudge(axis.get(), direction));
                }
            }
            case AiEffect.Promise.TYPE -> {
                String item = itemRef(json).orElse("");
                if (string(json, "item").filter(i -> !i.isBlank()).isPresent() && item.isEmpty()) {
                    return Optional.empty(); // an item was named but is not an item id: not a promise we can check
                }
                int count = item.isEmpty() ? 0 : clampInt(json, "count", 1, 1, MAX_PROMISE_COUNT);
                int days = clampInt(json, "days", 1, 1, MAX_PROMISE_DAYS);
                return Optional.of(new AiEffect.Promise(item, count, days, summary(json)));
            }
            case AiEffect.Wish.TYPE -> {
                Optional<String> item = itemRef(json);
                if (item.isPresent()) {
                    return Optional.of(new AiEffect.Wish(item.get(), clampInt(json, "days", 5, 1, MAX_WISH_DAYS), summary(json)));
                }
            }
            case AiEffect.OfferQuest.TYPE -> {
                Optional<String> quest = string(json, "quest").map(q -> q.trim().toLowerCase(Locale.ROOT))
                        .filter(q -> q.length() <= MAX_TOKEN && RESOURCE_ID.matcher(q).matches());
                if (quest.isPresent()) {
                    return Optional.of(new AiEffect.OfferQuest(quest.get()));
                }
            }
            case AiEffect.UnlockTopic.TYPE -> {
                Optional<String> topic = token(json, "topic");
                if (topic.isPresent()) {
                    return Optional.of(new AiEffect.UnlockTopic(topic.get()));
                }
            }
            case AiEffect.Opinion.TYPE -> {
                String about = AiText.clean(string(json, "about").orElse(""), MAX_TOKEN);
                String axis = string(json, "axis").map(a -> a.trim().toLowerCase(Locale.ROOT)).orElse("");
                int direction = direction(json);
                if (!about.isEmpty() && OPINION_AXES.contains(axis) && direction != 0) {
                    return Optional.of(new AiEffect.Opinion(about, axis, direction,
                            AiText.clean(string(json, "cause").orElse(""), AiText.MAX_MEMORY)));
                }
            }
            case AiEffect.Directions.TYPE -> {
                Optional<String> place = token(json, "place");
                if (place.isPresent()) {
                    return Optional.of(new AiEffect.Directions(place.get()));
                }
            }
            case AiEffect.Discount.TYPE -> {
                return Optional.of(new AiEffect.Discount());
            }
            case AiEffect.Forgive.TYPE -> {
                return Optional.of(new AiEffect.Forgive());
            }
            case AiEffect.Grudge.TYPE -> {
                return Optional.of(new AiEffect.Grudge());
            }
            default -> {
                // Unknown effect types are dropped: the model cannot reach anything not written here.
            }
        }
        return Optional.empty();
    }

    /** A normalised item reference ({@code minecraft:wheat}, {@code #minecraft:logs}), or empty. */
    private static Optional<String> itemRef(JsonObject json) {
        return string(json, "item").map(i -> i.trim().toLowerCase(Locale.ROOT).replace(' ', '_'))
                .filter(i -> !i.isEmpty() && i.length() <= MAX_TOKEN && ITEM_REF.matcher(i).matches())
                .map(i -> {
                    boolean tag = i.startsWith("#");
                    String id = tag ? i.substring(1) : i;
                    return (tag ? "#" : "") + (id.contains(":") ? id : "minecraft:" + id);
                });
    }

    private static Optional<String> token(JsonObject json, String key) {
        return string(json, key).map(t -> t.trim().toLowerCase(Locale.ROOT).replace(' ', '_'))
                .filter(t -> !t.isEmpty() && t.length() <= MAX_TOKEN && TOKEN.matcher(t).matches());
    }

    private static String summary(JsonObject json) {
        return AiText.clean(string(json, "summary").or(() -> string(json, "what")).orElse(""), AiText.MAX_MEMORY);
    }

    private static int clampInt(JsonObject json, String key, int fallback, int min, int max) {
        JsonElement element = json.get(key);
        int value = fallback;
        if (element != null && element.isJsonPrimitive()) {
            try {
                value = (int) Math.round(element.getAsJsonPrimitive().isNumber()
                        ? element.getAsDouble() : Double.parseDouble(element.getAsString().trim()));
            } catch (RuntimeException ignored) {
                value = fallback;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private static boolean bool(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                && element.getAsBoolean();
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
