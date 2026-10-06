package dev.otectus.mcaconversations.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What came back from the endpoint: the assistant's text, or an error code. Exactly one is present.
 *
 * <p>Errors keep MCA's vocabulary where MCA has one ({@code limit}, {@code limit_premium},
 * {@code invalid_model} from MCA's hosted service) so the player sees MCA's own messages for them.
 */
public record AiHttpResult(Optional<String> content, Optional<String> error) {

    /** Largest response body accepted; anything bigger is an error, not something to parse. */
    static final int MAX_BODY_CHARS = 262_144;

    /** A reasoning model's thinking, inline in the content: {@code <think>} or {@code <thinking>} tags. */
    private static final Pattern THINK_TAG = Pattern.compile("<(/?)think(?:ing)?\\s*>", Pattern.CASE_INSENSITIVE);

    public static AiHttpResult ok(String content) {
        return new AiHttpResult(Optional.of(content), Optional.empty());
    }

    public static AiHttpResult failed(String error) {
        return new AiHttpResult(Optional.empty(), Optional.of(error == null || error.isBlank() ? "unknown" : error));
    }

    /**
     * Reads an OpenAI-compatible chat-completions response, or the {@code {"error": ...}} shape both
     * OpenAI (an object with a message) and MCA's hosted service (a bare code string) use.
     */
    public static AiHttpResult parse(int status, String body) {
        if (body == null || body.isBlank()) {
            return failed(status >= 200 && status < 300 ? "empty_response" : "http_" + status);
        }
        if (body.length() > MAX_BODY_CHARS) {
            return failed("response_too_large");
        }
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonObject()) {
                return failed("malformed_response");
            }
            json = element.getAsJsonObject();
        } catch (RuntimeException e) {
            return failed(status >= 200 && status < 300 ? "malformed_response" : "http_" + status);
        }
        if (json.has("error") && !json.get("error").isJsonNull()) {
            JsonElement error = json.get("error");
            if (error.isJsonPrimitive()) {
                return failed(error.getAsString().trim());
            }
            if (error.isJsonObject()) {
                JsonObject object = error.getAsJsonObject();
                for (String key : new String[]{"code", "type", "message"}) {
                    if (object.has(key) && object.get(key).isJsonPrimitive()) {
                        return failed(object.get(key).getAsString().trim());
                    }
                }
            }
            return failed("http_" + status);
        }
        if (status < 200 || status >= 300) {
            return failed("http_" + status);
        }
        try {
            JsonArray choices = json.getAsJsonArray("choices");
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            JsonElement content = message.get("content");
            // A reasoning model that spent its whole budget thinking answers with only
            // "reasoning_content" and a null content: nothing was said.
            if (content == null || content.isJsonNull()) {
                return failed("empty_response");
            }
            String text;
            if (content.isJsonPrimitive()) {
                text = content.getAsString();
            } else if (content.isJsonArray()) {
                text = textParts(content.getAsJsonArray());
            } else {
                return failed("malformed_response");
            }
            text = stripReasoning(text);
            return text.isBlank() ? failed("empty_response") : ok(text);
        } catch (RuntimeException e) {
            return failed("malformed_response");
        }
    }

    /** Content as an array of parts ({@code [{"type":"text","text":"..."}]}): the text parts, joined. */
    private static String textParts(JsonArray parts) {
        StringBuilder out = new StringBuilder();
        for (JsonElement part : parts) {
            if (part.isJsonPrimitive()) {
                out.append(part.getAsString());
                continue;
            }
            if (!part.isJsonObject()) {
                continue;
            }
            JsonObject object = part.getAsJsonObject();
            String type = object.has("type") && object.get("type").isJsonPrimitive()
                    ? object.get("type").getAsString().toLowerCase(Locale.ROOT) : "text";
            if ((type.equals("text") || type.equals("output_text"))
                    && object.has("text") && object.get("text").isJsonPrimitive()) {
                out.append(object.get("text").getAsString());
            }
        }
        return out.toString();
    }

    /**
     * The text without a reasoning model's thinking: every {@code <think>…</think>} (or
     * {@code <thinking>}) block goes, an opening tag never closed takes the rest with it, and a closing
     * tag with no opening (the template ate it) takes everything before it. Pure, never null.
     */
    static String stripReasoning(String text) {
        if (text == null) {
            return "";
        }
        Matcher tag = THINK_TAG.matcher(text);
        if (text.indexOf('<') < 0 || !tag.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int depth = 0;
        int from = 0;
        do {
            boolean closing = !tag.group(1).isEmpty();
            if (closing && depth == 0) {
                out.setLength(0); // a dangling close: all so far was thinking
            } else if (!closing && depth == 0) {
                out.append(text, from, tag.start());
            }
            depth = closing ? Math.max(0, depth - 1) : depth + 1;
            from = tag.end();
        } while (tag.find());
        if (depth == 0) {
            out.append(text, from, text.length());
        }
        return out.toString().strip();
    }
}
