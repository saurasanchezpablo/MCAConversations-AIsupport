package dev.otectus.mcaconversations.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPromptBuilderTest {

    private static AiPromptInput input(boolean inHouse, String systemPrompt, List<AiPromptInput.CommandOption> commands,
                                       boolean jsonMode) {
        return new AiPromptInput("gpt-test", systemPrompt, inHouse, false, true, false, null, jsonMode, 42L,
                UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                UUID.fromString("00000000-0000-0000-0000-00000000a11c"), "Steve", "Alice",
                "This is a conversation with a female Minecraft villager named Alice.", "Villager background: baker",
                "", List.of(new AiContextSection("Relationship with Steve", List.of("Relationship: friend")),
                        new AiContextSection("Empty", List.of())),
                List.of(new AiMemory("Steve fixed my roof.", AiImportance.HIGH, AiSentiment.POSITIVE, 8)), 10,
                commands, List.of(new AiSessions.Line(true, "Hi \"Alice\""), new AiSessions.Line(false, "Hello!")),
                "How are you?");
    }

    @Test
    void theBodyIsAValidChatCompletionsRequest() {
        JsonObject body = JsonParser.parseString(AiPromptBuilder.body(input(false, "", List.of(), false))).getAsJsonObject();
        assertEquals("gpt-test", body.get("model").getAsString());
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("Hi \"Alice\"", messages.get(1).getAsJsonObject().get("content").getAsString());
        assertEquals("assistant", messages.get(2).getAsJsonObject().get("role").getAsString());
        assertEquals("How are you?", messages.get(3).getAsJsonObject().get("content").getAsString());
        assertFalse(body.has("response_format"));
        assertTrue(JsonParser.parseString(AiPromptBuilder.body(input(false, "", List.of(), true))).getAsJsonObject()
                .has("response_format"));
    }

    @Test
    void theSystemPromptKeepsMcasPiecesAndAddsStructure() {
        String system = AiPromptBuilder.system(input(false, "", List.of(), false));
        assertTrue(system.startsWith(AiPromptBuilder.MCA_DEFAULT_SYSTEM_PROMPT), "MCA's default prompt comes first");
        assertTrue(system.contains("Villager background: baker"));
        assertTrue(system.contains("named Alice"));
        assertTrue(system.contains("### Relationship with Steve\n- Relationship: friend"));
        assertFalse(system.contains("### Empty"), "an empty section is not rendered");
        assertTrue(system.contains("- Steve fixed my roof. (2 days ago, high importance)"));
        assertTrue(system.contains("\"assessment\""));
        assertFalse(system.contains("optionalCommand"), "no commands, no command field");
        assertFalse(system.contains("[world_id"), "session tags only when MCA would send them");
    }

    @Test
    void theHostedServiceGetsMcasSessionTagsAndNoDefaultPrompt() {
        String system = AiPromptBuilder.system(input(true, "", List.of(), false));
        assertTrue(system.startsWith("[world_id:42][player_id:00000000-0000-0000-0000-0000000000aa]"
                + "[character_id:00000000-0000-0000-0000-00000000a11c][use_memory:true]"));
        assertFalse(system.contains(AiPromptBuilder.MCA_DEFAULT_SYSTEM_PROMPT));
    }

    @Test
    void anOperatorPromptReplacesTheDefaultAndCommandsAreListed() {
        String system = AiPromptBuilder.system(input(false, "You are a pirate.",
                List.of(new AiPromptInput.CommandOption("follow-player", "Follow the player talking to you")), false));
        assertTrue(system.startsWith("You are a pirate."));
        assertTrue(system.contains("optionalCommand"));
        assertTrue(system.contains("  * follow-player: Follow the player talking to you"));
    }
}
