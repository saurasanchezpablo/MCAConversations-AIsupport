package dev.otectus.mcaconversations.ai;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiHttpResultTest {

    @Test
    void anOpenAiResponseYieldsItsContent() {
        AiHttpResult result = AiHttpResult.parse(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"message\\\":\\\"Hi\\\"}\"}}]}");
        assertEquals(Optional.of("{\"message\":\"Hi\"}"), result.content());
    }

    @Test
    void mcasHostedErrorsKeepMcasCodes() {
        assertEquals(Optional.of("limit"), AiHttpResult.parse(200, "{\"error\": \"limit\"}").error());
        assertEquals(Optional.of("invalid_model"), AiHttpResult.parse(400, "{\"error\": \"invalid_model\"}").error());
    }

    @Test
    void openAiErrorObjectsAndHttpFailuresAreErrors() {
        assertEquals(Optional.of("rate_limit_exceeded"), AiHttpResult.parse(429,
                "{\"error\": {\"message\": \"slow down\", \"code\": \"rate_limit_exceeded\"}}").error());
        assertEquals(Optional.of("http_502"), AiHttpResult.parse(502, "<html>Bad gateway</html>").error());
        assertEquals(Optional.of("malformed_response"), AiHttpResult.parse(200, "{\"choices\": []}").error());
        assertEquals(Optional.of("empty_response"), AiHttpResult.parse(200, "").error());
        assertEquals(Optional.of("response_too_large"),
                AiHttpResult.parse(200, "x".repeat(AiHttpResult.MAX_BODY_CHARS + 1)).error());
    }
}
