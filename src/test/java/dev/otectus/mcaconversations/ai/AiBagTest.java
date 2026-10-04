package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.conversation.RelationshipBand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a villager takes a player going through their bag. */
class AiBagTest {

    @Test
    void thingsAreWorthWhatTheyAreWorth() {
        assertEquals(30, AiBag.value("minecraft:diamond", 3, false, false));
        assertEquals(90, AiBag.value("minecraft:diamond_block", 1, false, false));
        assertEquals(4, AiBag.value("minecraft:iron_axe", 1, false, true));
        assertEquals(4, AiBag.value("minecraft:book", 1, true, false), "enchanted things are worth more");
        assertEquals(1, AiBag.value("minecraft:bread", 3, false, false));
        assertEquals(0, AiBag.value("minecraft:rotten_flesh", 20, false, false));
    }

    @Test
    void howCloseYouAreDecidesHowTheyTakeIt() {
        assertEquals(AiBag.Verdict.FINE, AiBag.judge(RelationshipBand.STRANGER, true, 10), "family hardly minds");
        assertEquals(AiBag.Verdict.MILD, AiBag.judge(RelationshipBand.STRANGER, true, 30), "but a fortune is noticed");
        assertEquals(AiBag.Verdict.FINE, AiBag.judge(RelationshipBand.FRIEND, false, 3));
        assertEquals(AiBag.Verdict.MILD, AiBag.judge(RelationshipBand.FRIEND, false, 12));
        assertEquals(AiBag.Verdict.UPSET, AiBag.judge(RelationshipBand.FRIEND, false, 40));
        assertEquals(AiBag.Verdict.UPSET, AiBag.judge(RelationshipBand.ACQUAINTANCE, false, 8));
        assertEquals(AiBag.Verdict.THEFT, AiBag.judge(RelationshipBand.ACQUAINTANCE, false, 30));
        assertEquals(AiBag.Verdict.THEFT, AiBag.judge(RelationshipBand.STRANGER, false, 10));
        assertEquals(AiBag.Verdict.FINE, AiBag.judge(RelationshipBand.STRANGER, false, 0), "worthless is not a loss");
    }

    @Test
    void consequencesFollowTheVerdict() {
        assertEquals(0, AiBag.hearts(AiBag.Verdict.FINE));
        assertTrue(AiBag.hearts(AiBag.Verdict.THEFT) < AiBag.hearts(AiBag.Verdict.UPSET));
        assertTrue(AiBag.feeling(AiBag.Verdict.THEFT).contains("stealing"));
        assertEquals("3 diamond, an iron axe", AiBag.list(List.of(new AiBag.Moved("minecraft:diamond", "diamond", 3, 30, false),
                new AiBag.Moved("minecraft:iron_axe", "iron axe", 1, 4, true))));
    }
}
