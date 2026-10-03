package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.disposition.DispositionAxis;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiReplyParserTest {

    @Test
    void aFullStructuredReplyParsesEveryField() {
        AiReply reply = AiReplyParser.parse("""
                {"message": "That means a lot to me.", "optionalCommand": "follow-player",
                 "assessment": {"impact": "positive", "confidence": 0.9},
                 "emotion": "grateful",
                 "memory": {"text": "They thanked me for guarding the gate.", "importance": "high"},
                 "effects": [{"type": "disposition", "axis": "trust", "direction": "up"}]}
                """).orElseThrow();
        assertTrue(reply.structured());
        assertEquals("That means a lot to me.", reply.dialogue());
        assertEquals("follow-player", reply.command());
        assertEquals(AiSentiment.POSITIVE, reply.sentiment());
        assertEquals(0.9, reply.confidence(), 1e-9);
        assertEquals(AiEmotion.GRATEFUL, reply.emotion());
        assertEquals(AiImportance.HIGH, reply.memory().orElseThrow().importance());
        assertEquals(List.of(new AiEffect.DispositionNudge(DispositionAxis.TRUST, 1)), reply.effects());
    }

    @Test
    void mcasOwnTwoFieldFormatIsUnderstood() {
        AiReply reply = AiReplyParser.parse("{\"message\": \"Sure, lead the way.\", \"optionalCommand\": \"follow-player\"}")
                .orElseThrow();
        assertEquals("Sure, lead the way.", reply.dialogue());
        assertEquals("follow-player", reply.command());
        assertEquals(AiSentiment.NEUTRAL, reply.sentiment());
    }

    @Test
    void codeFencesAndSurroundingProseAreIgnored() {
        AiReply reply = AiReplyParser.parse("Here you go:\n```json\n{\"message\": \"Hello.\", \"impact\": \"negative\", "
                + "\"confidence\": \"80\"}\n```").orElseThrow();
        assertEquals("Hello.", reply.dialogue());
        assertEquals(AiSentiment.NEGATIVE, reply.sentiment());
        assertEquals(0.8, reply.confidence(), 1e-9, "a percentage is read as a fraction");
    }

    @Test
    void plainTextIsDialogueWithNoEffects() {
        AiReply reply = AiReplyParser.parse("Lovely weather today, isn't it?").orElseThrow();
        assertFalse(reply.structured());
        assertEquals(AiSentiment.NEUTRAL, reply.sentiment());
        assertTrue(reply.effects().isEmpty());
        assertTrue(reply.memory().isEmpty());
    }

    @Test
    void brokenJsonSalvagesOnlyTheLine() {
        AiReply reply = AiReplyParser.parse("{\"message\": \"I \\\"suppose\\\" so.\", \"impact\": \"strongly_positive\", ")
                .orElseThrow();
        assertFalse(reply.structured(), "a reply that is not valid JSON must not carry a judgement");
        assertEquals("I \"suppose\" so.", reply.dialogue());
        assertEquals(AiSentiment.NEUTRAL, reply.sentiment());
    }

    @Test
    void nothingUsableIsEmpty() {
        assertTrue(AiReplyParser.parse(null).isEmpty());
        assertTrue(AiReplyParser.parse("   ").isEmpty());
        assertTrue(AiReplyParser.parse("{\"impact\": \"positive\"}").isEmpty(), "no message, no reply");
        assertTrue(AiReplyParser.parse("{ broken").isEmpty());
    }

    @Test
    void unknownVocabularyFallsBackToHarmlessValues() {
        AiReply reply = AiReplyParser.parse("{\"message\": \"Hm.\", \"impact\": \"ecstatic\", \"confidence\": 7.5, "
                + "\"emotion\": \"homicidal\", \"optionalCommand\": \"/op @a\"}").orElseThrow();
        assertEquals(AiSentiment.NEUTRAL, reply.sentiment());
        assertEquals(AiEmotion.NEUTRAL, reply.emotion());
        assertEquals("", reply.command(), "a command id outside the id alphabet is dropped");
        assertEquals(0.075, reply.confidence(), 1e-9, "an out-of-range confidence reads as a percentage");
        assertEquals(0.0, AiReplyParser.parse("{\"message\": \"Hm.\", \"confidence\": 500}").orElseThrow().confidence(),
                1e-9, "and never inflates");
    }

    @Test
    void onlyKnownEffectTypesAndNudgeableAxesSurvive() {
        AiReply reply = AiReplyParser.parse("""
                {"message": "Fine.", "effects": [
                  {"type": "give_item", "item": "minecraft:diamond", "count": 64},
                  {"type": "disposition", "axis": "familiarity", "direction": "up"},
                  {"type": "disposition", "axis": "tension", "direction": "sideways"},
                  {"type": "launch_fireworks"},
                  {"type": "disposition", "axis": "warmth", "direction": "up"}]}
                """).orElseThrow();
        // Only the first MAX_EFFECTS entries are looked at, and none of those four is legal.
        assertTrue(reply.effects().isEmpty());

        AiReply legal = AiReplyParser.parse("{\"message\": \"Fine.\", \"effects\": ["
                + "{\"type\": \"disposition\", \"axis\": \"respect\", \"direction\": -5}]}").orElseThrow();
        assertEquals(List.of(new AiEffect.DispositionNudge(DispositionAxis.RESPECT, -1)), legal.effects(),
                "a magnitude from the model is reduced to a direction");
    }

    @Test
    void textIsSanitisedAndBounded() {
        String longLine = "a".repeat(5000);
        AiReply reply = AiReplyParser.parse("{\"message\": \"\\u00a7cRed\\u0007 " + longLine + "\", "
                + "\"memory\": \"\\u00a7k" + longLine + "\"}").orElseThrow();
        assertFalse(reply.dialogue().contains("§"));
        assertFalse(reply.dialogue().contains("\u0007"));
        assertTrue(reply.dialogue().startsWith("Red "));
        assertTrue(reply.dialogue().codePointCount(0, reply.dialogue().length()) <= AiText.MAX_DIALOGUE);
        String memory = reply.memory().orElseThrow().text();
        assertTrue(memory.codePointCount(0, memory.length()) <= AiText.MAX_MEMORY);
        assertEquals(AiImportance.MEDIUM, reply.memory().orElseThrow().importance());
    }
}
