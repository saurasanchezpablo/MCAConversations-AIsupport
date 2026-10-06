package dev.otectus.mcaconversations.ai;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiDirectionsTest {

    @Test
    void compassFollowsMinecraftAxes() {
        assertEquals("n", AiDirections.compassKey(0, -100));
        assertEquals("s", AiDirections.compassKey(0, 100));
        assertEquals("e", AiDirections.compassKey(100, 0));
        assertEquals("w", AiDirections.compassKey(-100, 0));
        assertEquals("ne", AiDirections.compassKey(70, -70));
        assertEquals("sw", AiDirections.compassKey(-70, 70));
        assertEquals("here", AiDirections.compassKey(0, 0));
    }

    @Test
    void distancesAreRoundedTheWayPeopleSayThem() {
        assertEquals(40, AiDirections.blocks(BlockPos.ZERO, new BlockPos(41, 0, 0)));
        assertEquals(5, AiDirections.blocks(BlockPos.ZERO, new BlockPos(1, 0, 0)));
        assertEquals(350, AiDirections.blocks(BlockPos.ZERO, new BlockPos(340, 0, 0)));
    }

    @Test
    void theSearchCooldownLetsTheFirstSearchRunAndSurvivesAClockSetBack() {
        assertTrue(AiDirections.searchReady(Long.MIN_VALUE, 0), "no search yet: never on cooldown");
        assertTrue(AiDirections.searchReady(Long.MIN_VALUE, 5_000_000));
        assertFalse(AiDirections.searchReady(1_000, 1_000 + AiDirections.SEARCH_COOLDOWN_TICKS - 1));
        assertTrue(AiDirections.searchReady(1_000, 1_000 + AiDirections.SEARCH_COOLDOWN_TICKS));
        assertTrue(AiDirections.searchReady(1_000, 10), "time set back: not stuck waiting");
    }
}
