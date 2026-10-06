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

    @Test
    void namesInScriptsWithoutSpacesAreFoundInsideTheLine() {
        assertTrue(AiAddressing.mentions("小明你好", "小明"));
        assertTrue(AiAddressing.mentions("太郎さん、こんにちは", "太郎"));
        assertTrue(AiAddressing.mentions("철수야 뭐 해?", "철수"));
        assertTrue(AiAddressing.mentions("王小明，你好", "王小明"));
        assertTrue(AiAddressing.mentions("小明，过来", "小明 王"), "a two-character given name is enough");
        assertFalse(AiAddressing.mentions("你好", "小明"));
    }

    @Test
    void aNameThatIsAlsoAWordCountsOnlyWhenUsedAsOne() {
        assertFalse(AiAddressing.mentions("I will go to the mine", "Will"));
        assertFalse(AiAddressing.mentions("Will you sell me bread?", "Will"), "a capital that only starts the sentence");
        assertFalse(AiAddressing.mentions("may I come in", "May Rivers"));
        assertFalse(AiAddressing.mentions("what a lovely rose garden", "Rose"));
        assertFalse(AiAddressing.mentions("hace sol hoy", "Sol"));
        assertTrue(AiAddressing.mentions("Will, can you sell me bread?", "Will"));
        assertTrue(AiAddressing.mentions("thanks rose!", "Rose"));
        assertTrue(AiAddressing.mentions("have you seen Will today", "Will"), "capitalised mid-sentence is a name");
        assertTrue(AiAddressing.mentions("hola sol", "Sol"), "ending the line, it is a call");
        assertTrue(AiAddressing.mentions("¿Qué tal, Paz?", "Paz"));
    }
}
