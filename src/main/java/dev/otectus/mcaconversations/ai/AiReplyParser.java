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
        String text = withoutThinking(FENCE.matcher(content).replaceAll("")).strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (text.indexOf('{') < 0) {
            return line(text);
        }
        for (JsonObject json : objects(text)) {
            Optional<AiReply> structured = fromJson(json);
            if (structured.isPresent()) {
                return structured;
            }
        }
        // Broken or truncated JSON: salvage the line if the message field is recognisable, never the
        // effects, and never the raw JSON itself as something a villager says.
        Matcher m = MESSAGE_FIELD.matcher(text);
        return m.find() ? line(unescape(m.group(1))) : Optional.empty();
    }

    private static final Pattern THINKING = Pattern.compile("(?is)<(think|thinking|reasoning)>.*?</\\1>");
    private static final Pattern THINKING_END = Pattern.compile("(?is)^.*</(think|thinking|reasoning)>");
    private static final Pattern THINKING_OPEN = Pattern.compile("(?is)<(think|thinking|reasoning)>.*$");

    /**
     * The text without a reasoning model's thinking ({@code <think>...</think>}): a draft in there must
     * never be taken for the reply, nor spoken. An unclosed block (cut off) is dropped to the end; text
     * before a dangling closing tag is dropped too.
     */
    static String withoutThinking(String text) {
        String out = THINKING.matcher(text).replaceAll("");
        out = THINKING_END.matcher(out).replaceAll("");
        return THINKING_OPEN.matcher(out).replaceAll("");
    }

    /**
     * Every complete JSON object in the text, in order, found by a scan that respects strings: prose
     * around the reply, or braces in a note after it, do not break it.
     */
    static List<JsonObject> objects(String text) {
        List<JsonObject> out = new ArrayList<>();
        int i = 0;
        while (i < text.length() && out.size() < 8) {
            int start = text.indexOf('{', i);
            if (start < 0) {
                break;
            }
            int end = matchingBrace(text, start);
            if (end < 0) {
                break; // unbalanced to the end: nothing complete from here on
            }
            JsonObject json = parseObject(text.substring(start, end + 1));
            if (json != null) {
                out.add(json);
                i = end + 1;
            } else {
                i = start + 1;
            }
        }
        return out;
    }

    private static int matchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A reading of an exchange on its own ({@link AiJudge}): {@code {"request": ..., "received": ...}}, where
     * {@code null} fields mean "nothing asked, nothing received". Anything without either field is empty,
     * so the caller knows the reading failed.
     */
    public static Optional<AiUnderstanding> parseUnderstanding(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String text = withoutThinking(FENCE.matcher(content).replaceAll("")).strip();
        for (JsonObject json : objects(text)) {
            Optional<AiUnderstanding> reading = understanding(json);
            if (reading.isPresent()) {
                return reading;
            }
        }
        return Optional.empty(); // neither field anywhere: the endpoint did not do what it was asked
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
                interjection, delivery, true, understanding(json)));
    }

    /**
     * The model's reading of the exchange: {@code "request"} (what the player asked for, as an action with
     * its details, and {@code "answer"}) and {@code "received"} (what the villager's line says they were
     * given). Empty when the model wrote neither field, so the caller knows it has to find out otherwise.
     */
    static Optional<AiUnderstanding> understanding(JsonObject json) {
        boolean hasRequest = json.has("request") || json.has("player_request");
        boolean hasReceived = json.has("received");
        if (!hasRequest && !hasReceived) {
            return Optional.empty();
        }
        JsonElement element = json.has("request") ? json.get("request") : json.get("player_request");
        Optional<AiActionKind> asked = Optional.empty();
        Optional<AiEffect.Action> request = Optional.empty();
        AiUnderstanding.Answer answer = AiUnderstanding.Answer.byKey(string(json, "answer").orElse(null));
        if (element != null && element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            asked = string(object, "do").or(() -> string(object, "action")).flatMap(AiActionKind::byKey);
            if (asked.isPresent()) {
                JsonObject action = object.deepCopy();
                action.addProperty("type", AiEffect.Action.TYPE);
                request = parseEffect(action).filter(AiEffect.Action.class::isInstance).map(AiEffect.Action.class::cast);
            }
            if (object.has("answer")) {
                answer = AiUnderstanding.Answer.byKey(string(object, "answer").orElse(null));
            }
        } else if (element != null && element.isJsonPrimitive()) {
            asked = AiActionKind.byKey(element.getAsString()); // "request": "follow"
        }
        return Optional.of(new AiUnderstanding(asked, request, asked.isEmpty() ? AiUnderstanding.Answer.NONE : answer,
                received(json.get("received"))));
    }

    /** An item id, {@link AiUnderstanding#SOMETHING}, or empty for "nothing". */
    private static Optional<String> received(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return Optional.empty();
        }
        String raw = element.getAsString().trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty() || raw.equals("none") || raw.equals("nothing") || raw.equals("null") || raw.equals("false")) {
            return Optional.empty();
        }
        if (raw.equals("true") || raw.equals("something") || raw.equals("gift") || raw.equals("a gift")
                || raw.equals("present")) {
            return Optional.of(AiUnderstanding.SOMETHING);
        }
        JsonObject holder = new JsonObject();
        holder.addProperty("item", raw);
        return Optional.of(itemRef(holder).filter(i -> !i.startsWith("#")).orElse(AiUnderstanding.SOMETHING));
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
            case AiEffect.Vote.TYPE -> {
                String candidate = AiText.clean(string(json, "for").or(() -> string(json, "candidate")).orElse(""), MAX_TOKEN);
                if (!candidate.isEmpty()) {
                    return Optional.of(new AiEffect.Vote(candidate));
                }
            }
            case AiEffect.Teach.TYPE -> {
                Optional<AiChore> task = string(json, "task").flatMap(AiChore::byKey);
                if (task.isPresent()) {
                    return Optional.of(new AiEffect.Teach(task.get()));
                }
            }
            case AiEffect.TeachRecipe.TYPE -> {
                Optional<String> item = itemRef(json).filter(i -> !i.startsWith("#"));
                if (item.isPresent()) {
                    return Optional.of(new AiEffect.TeachRecipe(item.get()));
                }
            }
            case AiEffect.SecretTold.TYPE -> {
                String about = AiText.clean(string(json, "about").orElse(""), MAX_TOKEN);
                if (!about.isEmpty()) {
                    return Optional.of(new AiEffect.SecretTold(about,
                            AiText.clean(string(json, "summary").orElse(""), AiText.MAX_MEMORY)));
                }
            }
            case AiEffect.Reconcile.TYPE -> {
                String with = AiText.clean(string(json, "with").or(() -> string(json, "about")).orElse(""), MAX_TOKEN);
                if (!with.isEmpty()) {
                    return Optional.of(new AiEffect.Reconcile(with));
                }
            }
            case AiEffect.Action.TYPE -> {
                Optional<AiActionKind> kind = string(json, "do").or(() -> string(json, "action")).flatMap(AiActionKind::byKey);
                if (kind.isEmpty()) {
                    return Optional.empty();
                }
                Optional<AiChore> chore = string(json, "task").flatMap(AiChore::byKey);
                if (kind.get() == AiActionKind.WORK && chore.isEmpty()) {
                    return Optional.empty(); // "go work" with no task named is not an order the game can follow
                }
                boolean needsItem = kind.get() == AiActionKind.GIVE || kind.get() == AiActionKind.FETCH;
                String rawItem = string(json, "item").map(i -> i.trim().toLowerCase(Locale.ROOT)).orElse("");
                boolean everything = kind.get() == AiActionKind.GIVE && (rawItem.isEmpty() || rawItem.equals("all")
                        || rawItem.equals("everything") || rawItem.equals("todo"));
                String item = everything ? AiIntent.ALL : needsItem
                        ? itemRef(json).filter(i -> kind.get() == AiActionKind.GIVE || !i.startsWith("#")).orElse("") : "";
                if (needsItem && item.isEmpty()) {
                    return Optional.empty();
                }
                boolean needsPlace = kind.get() == AiActionKind.GUIDE || kind.get() == AiActionKind.WAIT_AT;
                String place = needsPlace || kind.get() == AiActionKind.DATE ? token(json, "place").orElse("") : "";
                if (needsPlace && place.isEmpty()) {
                    return Optional.empty();
                }
                if (kind.get() == AiActionKind.DATE) {
                    // "when": now = 0, this evening = 1, tomorrow evening = 2 (carried in amount).
                    String when = string(json, "when").map(w -> w.trim().toLowerCase(Locale.ROOT)).orElse("evening");
                    int at = when.startsWith("now") || when.equals("ahora") ? 0 : when.startsWith("tomorrow")
                            || when.startsWith("mañana") ? 2 : 1;
                    return Optional.of(new AiEffect.Action(kind.get(), Optional.empty(), at, "",
                            place.equals("here") ? "" : place, List.of()));
                }
                if (kind.get() == AiActionKind.BUILD) {
                    Optional<String> plan = token(json, "build").or(() -> token(json, "plan"))
                            .filter(AiBuild.TEMPLATES::contains);
                    if (plan.isEmpty()) {
                        return Optional.empty();
                    }
                    return Optional.of(new AiEffect.Action(kind.get(), Optional.empty(), 0, plan.get(), "",
                            helpers(json.get("helpers"))));
                }
                int amount = clampInt(json, "amount", needsItem ? 1 : 0, 0, kind.get() == AiActionKind.WORK ? 256 : 64);
                return Optional.of(new AiEffect.Action(kind.get(), chore, amount, item, place, helpers(json.get("helpers"))));
            }
            default -> {
                // Unknown effect types are dropped: the model cannot reach anything not written here.
            }
        }
        return Optional.empty();
    }

    /** Helper names (bounded, cleaned), or {@code ["all"]} for "everyone"/"todos"/"all". */
    static java.util.List<String> helpers(JsonElement element) {
        java.util.List<String> out = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return out;
        }
        java.util.List<String> raw = new ArrayList<>();
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(e -> {
                if (e.isJsonPrimitive()) {
                    raw.add(e.getAsString());
                }
            });
        } else if (element.isJsonPrimitive()) {
            raw.add(element.getAsString());
        }
        for (String name : raw) {
            String clean = AiText.clean(name, MAX_TOKEN);
            String key = clean.toLowerCase(Locale.ROOT);
            if (key.equals("all") || key.equals("everyone") || key.equals("everybody") || key.equals("todos")) {
                return java.util.List.of(AiEffect.Action.ALL);
            }
            if (!clean.isEmpty() && out.size() < 6) {
                out.add(clean);
            }
        }
        return out;
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
