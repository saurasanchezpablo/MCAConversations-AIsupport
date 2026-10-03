package dev.otectus.mcaconversations.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Optional;

/**
 * What came back from the endpoint: the assistant's text, or an error code. Exactly one is present.
 *
 * <p>Errors keep MCA's vocabulary where MCA has one ({@code limit}, {@code limit_premium},
 * {@code invalid_model} from MCA's hosted service) so the player sees MCA's own messages for them.
 */
public record AiHttpResult(Optional<String> content, Optional<String> error) {

    /** Largest response body accepted; anything bigger is an error, not something to parse. */
    static final int MAX_BODY_CHARS = 262_144;

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
            if (content == null || content.isJsonNull()) {
                return failed("empty_response");
            }
            return ok(content.getAsString());
        } catch (RuntimeException e) {
            return failed("malformed_response");
        }
    }
}
