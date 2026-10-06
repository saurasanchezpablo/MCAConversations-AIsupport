package dev.otectus.mcaconversations.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiContextFormatTest {

    @Test
    void listedIdsBecomeWordsButNamesKeepTheirCase() {
        assertEquals("Alice, María José, Bob", AiContextFormat.list(List.of("Alice", "María José", "Bob")));
        assertEquals("lost ring, odd", AiContextFormat.list(List.of("lost_ring", "mca:odd")));
        assertEquals("town center", AiContextFormat.words("town_center"));
    }
}
