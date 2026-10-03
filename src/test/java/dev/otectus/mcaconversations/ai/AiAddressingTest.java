package dev.otectus.mcaconversations.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAddressingTest {

    @Test
    void namesAreFoundAsWholeWordsIgnoringCaseAndAccents() {
        assertTrue(AiAddressing.mentions("Hola Sofía, ¿qué tal?", "Sofia Torres"));
        assertTrue(AiAddressing.mentions("hey SOFIA", "Sofía"));
        assertTrue(AiAddressing.mentions("¿Sofia Torres, me ayudas?", "Sofía Torres"));
        assertFalse(AiAddressing.mentions("sofisticado", "Sofía"), "part of another word is not a name");
        assertFalse(AiAddressing.mentions("Hola a todos", "Sofía"));
    }

    @Test
    void veryShortFirstNamesNeedTheFullName() {
        assertFalse(AiAddressing.mentions("al final llegué", "Al Rivers"), "'al' is too common to count");
        assertTrue(AiAddressing.mentions("Al Rivers, hello", "Al Rivers"));
    }
}
