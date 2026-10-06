package dev.otectus.mcaconversations.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiTextTest {

    @Test
    void joinersSurviveAndOtherFormatCharactersVanishWithoutLeavingSpaces() {
        String persian = "می‌خواهم";
        assertEquals(persian, AiText.clean(persian, AiText.MAX_DIALOGUE), "ZWNJ is part of the word");
        String family = "👨‍👩‍👧";
        assertEquals(family, AiText.clean(family, AiText.MAX_DIALOGUE), "ZWJ holds an emoji sequence together");
        assertEquals("abcdef", AiText.clean("abc‮def", AiText.MAX_DIALOGUE), "a bidi override is dropped");
        assertEquals("abcdef", AiText.clean("a⁦b⁩c​def", AiText.MAX_DIALOGUE));
        assertEquals("a b", AiText.clean("a ‪ b", AiText.MAX_DIALOGUE));
    }
}
