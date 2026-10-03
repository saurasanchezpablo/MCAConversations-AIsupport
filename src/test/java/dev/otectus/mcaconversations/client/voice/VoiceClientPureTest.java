package dev.otectus.mcaconversations.client.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcaconversations.voice.VoiceDirection;
import dev.otectus.mcaconversations.voice.VoiceIntent;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts of the client voice that need no running client: matching, request shapes, parsing. */
class VoiceClientPureTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static VoiceDirection d(UUID who, String text, String emotion) {
        return new VoiceDirection(who, text, "es_es", emotion, VoiceIntent.STATEMENT, "", VoiceDirection.Pace.NORMAL, 0.5f,
                VoiceDirection.Volume.NORMAL, "female", "adult", "", "", false, false, false);
    }

    @Test
    void aDirectionFindsItsExactLineFirstThenTheNextLine() {
        PendingDirections pending = new PendingDirections();
        pending.add(d(ALICE, "", "grateful"), 0);
        pending.add(d(ALICE, "¡Gracias, de verdad!", "happy"), 0);
        assertEquals("happy", pending.take(ALICE, "¡GRACIAS, de verdad!!", 10).orElseThrow().emotion(),
                "matched by text, ignoring case and punctuation");
        assertEquals("grateful", pending.take(ALICE, "Something else entirely", 10).orElseThrow().emotion(),
                "an any-line direction applies to the next line");
        assertTrue(pending.take(ALICE, "again", 10).isEmpty());
        assertTrue(pending.take(BOB, "anything", 10).isEmpty(), "never another villager's direction");
    }

    @Test
    void staleDirectionsAreDropped() {
        PendingDirections pending = new PendingDirections();
        pending.add(d(ALICE, "Hola", "happy"), 0);
        assertTrue(pending.take(ALICE, "Hola", PendingDirections.MAX_AGE_MILLIS + 1).isEmpty());
    }

    @Test
    void openAiGetsTheLineVerbatimAndTheBriefAsInstructions() {
        JsonObject body = JsonParser.parseString(OpenAiSpeech.body("gpt-4o-mini-tts", "Hola, ¿qué tal?", "coral",
                d(ALICE, "Hola, ¿qué tal?", "happy"))).getAsJsonObject();
        assertEquals("Hola, ¿qué tal?", body.get("input").getAsString());
        assertEquals("coral", body.get("voice").getAsString());
        assertEquals("pcm", body.get("response_format").getAsString());
        assertTrue(body.get("instructions").getAsString().contains("Emotion: happy"));
    }

    @Test
    void geminiGetsAStyledPromptAndAudioIsDecoded() {
        JsonObject body = JsonParser.parseString(GeminiSpeech.body("Hola", "Kore", d(ALICE, "Hola", "sad"))).getAsJsonObject();
        String prompt = body.getAsJsonArray("contents").get(0).getAsJsonObject().getAsJsonArray("parts").get(0)
                .getAsJsonObject().get("text").getAsString();
        assertTrue(prompt.endsWith("\nHola"));
        assertTrue(prompt.contains("Emotion: sad"));
        assertEquals("Kore", body.getAsJsonObject("generationConfig").getAsJsonObject("speechConfig")
                .getAsJsonObject("voiceConfig").getAsJsonObject("prebuiltVoiceConfig").get("voiceName").getAsString());

        byte[] audio = {1, 2, 3, 4};
        Pcm pcm = GeminiSpeech.parse("{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":"
                + "\"audio/L16;codec=pcm;rate=16000\",\"data\":\"" + Base64.getEncoder().encodeToString(audio) + "\"}}]}}]}");
        assertArrayEquals(audio, pcm.data());
        assertEquals(16000, pcm.sampleRate());
    }

    @Test
    void keysAreNeverShownInFull() {
        assertEquals("(not set)", VoiceCommands.mask(""));
        assertEquals("****", VoiceCommands.mask("abc"));
        assertEquals("sk-p...wxyz", VoiceCommands.mask("sk-proj-1234567890wxyz"));
    }
}
