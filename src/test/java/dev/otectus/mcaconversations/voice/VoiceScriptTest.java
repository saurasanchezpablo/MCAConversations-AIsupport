package dev.otectus.mcaconversations.voice;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceScriptTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");

    private static VoiceDirection direction(String language, String emotion, VoiceIntent intent, VoiceDirection.Pace pace,
                                            VoiceDirection.Volume volume, boolean grieving, boolean romantic, boolean cold) {
        return new VoiceDirection(ALICE, "Hola, ¿qué tal?", language, emotion, intent, "warm, a little teasing", pace, 0.9f,
                volume, "female", "adult", "witty", "happy", grieving, romantic, cold);
    }

    @Test
    void theBriefCarriesEmotionIntentToneAndState() {
        String brief = VoiceScript.instructions(direction("es_es", "grateful", VoiceIntent.THANK, VoiceDirection.Pace.SLOW,
                VoiceDirection.Volume.WHISPER, true, true, true));
        assertTrue(brief.contains("an adult woman with a witty personality"), brief);
        assertTrue(brief.contains("Emotion: grateful, strong."), brief);
        assertTrue(brief.contains(VoiceIntent.THANK.direction()), brief);
        assertTrue(brief.contains("Tone: warm, a little teasing."), brief);
        assertTrue(brief.contains("slow"), brief);
        assertTrue(brief.contains("Whisper"), brief);
        assertTrue(brief.contains("grieving"), brief);
        assertTrue(brief.contains("tenderness"), brief);
        assertTrue(brief.contains("grudge"), brief);
        assertTrue(brief.contains("Do not add or change any words"), brief);
    }

    @Test
    void theAccentFollowsThePlayersGameLanguage() {
        assertTrue(VoiceScript.languageNote("es_es").contains("accent from Spain"));
        assertTrue(VoiceScript.languageNote("es_mx").contains("Latin American"));
        assertTrue(VoiceScript.languageNote("en_us").contains("American"));
        assertTrue(VoiceScript.languageNote("en_gb").contains("British"));
        assertTrue(VoiceScript.languageNote("es_es").contains("in the language it is written in"),
                "a line is spoken in its own language, never forced into another");
    }

    @Test
    void aNeutralLineGetsNoEmotionNorPaceNotes() {
        String brief = VoiceScript.instructions(new VoiceDirection(ALICE, "Hello.", "en_us", "neutral", VoiceIntent.GREET, "",
                VoiceDirection.Pace.NORMAL, 0.3f, VoiceDirection.Volume.NORMAL, "male", "child", "", "", false, false, false));
        assertFalse(brief.contains("Emotion:"));
        assertFalse(brief.contains("Pace:"));
        assertTrue(brief.contains("a young boy"));
    }

    @Test
    void directionsRoundTripTheWire() {
        VoiceDirection sent = direction("es_es", "angry", VoiceIntent.THREATEN, VoiceDirection.Pace.FAST,
                VoiceDirection.Volume.RAISED, false, false, true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        sent.write(buf);
        assertEquals(sent, VoiceDirection.read(buf));
    }

    @Test
    void directionFieldsAreSanitisedAndBounded() {
        VoiceDirection d = new VoiceDirection(ALICE, "x".repeat(5000), "ES_ES", "§cANGRY", null, "tone\u0007" + "y".repeat(500),
                null, Float.NaN, null, "FEMALE", "", "", "", false, false, false);
        assertEquals(VoiceDirection.MAX_TEXT, d.text().length());
        assertEquals("es_es", d.language());
        assertEquals("cangry", d.emotion());
        assertFalse(d.tone().contains("\u0007"));
        assertTrue(d.tone().length() <= VoiceDirection.MAX_FIELD);
        assertEquals(VoiceIntent.STATEMENT, d.intent());
        assertEquals(0.5f, d.intensity());
    }

    @Test
    void eachVillagerKeepsOneVoiceThatFitsThem() {
        String first = VoiceCatalog.voiceFor(VoiceProvider.OPENAI, ALICE, "female", "adult");
        assertEquals(first, VoiceCatalog.voiceFor(VoiceProvider.OPENAI, ALICE, "female", "adult"));
        assertTrue(VoiceCatalog.OPENAI_FEMALE.contains(first));
        assertTrue(VoiceCatalog.GEMINI_MALE.contains(VoiceCatalog.voiceFor(VoiceProvider.GEMINI, ALICE, "male", "adult")));
        assertTrue(VoiceCatalog.GEMINI_CHILD.contains(VoiceCatalog.voiceFor(VoiceProvider.GEMINI, ALICE, "female", "child")));
        long distinct = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> VoiceCatalog.voiceFor(VoiceProvider.GEMINI, UUID.randomUUID(), "male", "adult")).distinct().count();
        assertTrue(distinct > 5, "villagers are spread across the voices");
        assertNotEquals(VoiceIntent.TEASE.direction(), VoiceIntent.WARN.direction());
    }
}
