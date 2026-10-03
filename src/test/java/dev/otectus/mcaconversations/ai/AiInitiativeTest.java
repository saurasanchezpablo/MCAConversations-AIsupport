package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.conversation.RelationshipBand;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiInitiativeTest {

    private static AiInitiative.Facts facts(RelationshipBand band, boolean romantic, int turns, long days,
                                            String due, String kept, String loss, String heard, String wish) {
        return new AiInitiative.Facts("Steve", band, romantic, turns, days, Optional.ofNullable(due),
                Optional.ofNullable(kept), Optional.ofNullable(loss), Optional.ofNullable(heard), Optional.ofNullable(wish));
    }

    @Test
    void thePressingReasonWins() {
        AiInitiative.Reason reason = AiInitiative.reason(facts(RelationshipBand.FRIEND, true, 4, 5, "3 wheat",
                null, "your sister Mara", "Steve was kind to Bob", "a blue orchid")).orElseThrow();
        assertEquals(5, reason.weight());
        assertTrue(reason.text().contains("3 wheat"));
    }

    @Test
    void griefIsSharedOnlyWithSomeoneKnown() {
        assertEquals(1, AiInitiative.reason(facts(RelationshipBand.STRANGER, false, 0, 0, null, null,
                "your sister Mara", null, null)).orElseThrow().weight(), "a stranger only gets curiosity");
        assertEquals(4, AiInitiative.reason(facts(RelationshipBand.ACQUAINTANCE, false, 2, 0, null, null,
                "your sister Mara", null, null)).orElseThrow().weight());
    }

    @Test
    void absenceMattersToFriendsAndStrangersAreMetWithCuriosity() {
        assertEquals(3, AiInitiative.reason(facts(RelationshipBand.FRIEND, false, 9, 4, null, null, null, null, null))
                .orElseThrow().weight());
        assertTrue(AiInitiative.reason(facts(RelationshipBand.STRANGER, false, 0, 0, null, null, null, null, null))
                .orElseThrow().text().contains("never spoken"));
    }

    @Test
    void aVillagerWhoDislikesThePlayerDoesNotSeekThemOut() {
        assertFalse(AiInitiative.reason(facts(RelationshipBand.HOSTILE, false, 3, 9, "3 wheat", null, null, null, null)).isPresent());
        assertFalse(AiInitiative.reason(facts(RelationshipBand.TENSE, false, 3, 9, null, null, null, null, null)).isPresent());
    }

    @Test
    void strongerReasonsAreLikelierButNeverCertainBeyondOne() {
        assertEquals(0.1, AiInitiative.chance(new AiInitiative.Reason(1, ""), 0.1), 1e-9);
        assertEquals(0.5, AiInitiative.chance(new AiInitiative.Reason(5, ""), 0.1), 1e-9);
        assertEquals(1.0, AiInitiative.chance(new AiInitiative.Reason(5, ""), 0.5), 1e-9);
    }

    @Test
    void theOpenerNeverPretendsThePlayerSpoke() {
        String instruction = AiConversations.openerInstruction("Steve", "you heard Steve was kind to Bob");
        assertTrue(instruction.startsWith("[Steve has not said anything."));
        assertTrue(instruction.contains("you heard Steve was kind to Bob"));
    }
}
