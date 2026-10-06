package dev.otectus.mcaconversations.ai;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiMemoryStoreTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private static AiMemoryNote note(String text, AiImportance importance) {
        return new AiMemoryNote(text, importance);
    }

    @Test
    void memoriesBelongToExactlyOneVillagerPlayerPair() {
        AiMemoryStore store = new AiMemoryStore();
        store.touch(ALICE, PLAYER_A).remember(note("A praised my bread.", AiImportance.HIGH), AiSentiment.POSITIVE, 10, 12);
        assertTrue(store.get(ALICE, PLAYER_B).isEmpty(), "another player must not see Alice's memory of A");
        assertTrue(store.get(BOB, PLAYER_A).isEmpty(), "another villager must not hold Alice's memory of A");
        assertEquals(1, store.get(ALICE, PLAYER_A).orElseThrow().recall(10, 8).size());
    }

    @Test
    void theCapEvictsTheLeastImportantOldestFirst() {
        AiPairMemory pair = new AiPairMemory();
        pair.remember(note("high old", AiImportance.HIGH), AiSentiment.POSITIVE, 1, 3);
        pair.remember(note("low old", AiImportance.LOW), AiSentiment.NEUTRAL, 2, 3);
        pair.remember(note("medium", AiImportance.MEDIUM), AiSentiment.NEUTRAL, 3, 3);
        pair.remember(note("low new", AiImportance.LOW), AiSentiment.NEUTRAL, 4, 3);
        List<String> kept = pair.recall(4, 10).stream().map(AiMemory::text).toList();
        assertEquals(List.of("high old", "medium", "low new"), kept);
    }

    @Test
    void repeatingAMemoryReinforcesItInsteadOfFillingTheList() {
        AiPairMemory pair = new AiPairMemory();
        pair.remember(note("They insulted my profession!", AiImportance.HIGH), AiSentiment.NEGATIVE, 1, 12);
        pair.remember(note("they insulted my profession", AiImportance.LOW), AiSentiment.NEGATIVE, 5, 12);
        assertEquals(1, pair.size());
        AiMemory memory = pair.recall(5, 1).get(0);
        assertEquals(AiImportance.HIGH, memory.importance(), "reinforcing keeps the higher importance");
        assertEquals(5, memory.day());
    }

    @Test
    void memoriesFadeByImportance() {
        AiPairMemory pair = new AiPairMemory();
        pair.remember(note("low", AiImportance.LOW), AiSentiment.NEUTRAL, 0, 12);
        pair.remember(note("medium", AiImportance.MEDIUM), AiSentiment.NEUTRAL, 0, 12);
        pair.remember(note("high", AiImportance.HIGH), AiSentiment.NEUTRAL, 0, 12);
        assertEquals(3, pair.recall(7, 10).size());
        assertEquals(List.of("high", "medium"), pair.recall(8, 10).stream().map(AiMemory::text).toList());
        assertEquals(List.of("high"), pair.recall(31, 10).stream().map(AiMemory::text).toList());
        assertEquals(List.of("high"), pair.recall(10_000, 10).stream().map(AiMemory::text).toList());
    }

    @Test
    void aZeroCapStoresNothingAndNoCapExceedsTheHardLimit() {
        AiPairMemory pair = new AiPairMemory();
        assertFalse(pair.remember(note("x", AiImportance.HIGH), AiSentiment.NEUTRAL, 0, 0));
        for (int i = 0; i < 100; i++) {
            pair.remember(note("memory number " + i, AiImportance.HIGH), AiSentiment.NEUTRAL, i, 1000);
        }
        assertEquals(AiPairMemory.HARD_MAX_MEMORIES, pair.size());
    }

    @Test
    void theStoreEvictsTheLeastRecentlyWrittenPair() {
        AiMemoryStore store = new AiMemoryStore(2);
        store.touch(ALICE, PLAYER_A);
        store.touch(BOB, PLAYER_A);
        store.touch(ALICE, PLAYER_A);
        store.touch(ALICE, PLAYER_B);
        assertEquals(2, store.size());
        assertTrue(store.get(BOB, PLAYER_A).isEmpty());
        assertTrue(store.get(ALICE, PLAYER_A).isPresent());
    }

    @Test
    void aDeadVillagerTakesOnlyItsOwnMemories() {
        AiMemoryStore store = new AiMemoryStore();
        store.touch(ALICE, PLAYER_A).recordTurn(1);
        store.touch(ALICE, PLAYER_B).recordTurn(1);
        store.touch(BOB, PLAYER_A).recordTurn(1);
        assertEquals(2, store.removeVillager(ALICE));
        assertTrue(store.get(BOB, PLAYER_A).isPresent());
    }

    @Test
    void nbtRoundTripKeepsEveryPairApart() {
        AiMemoryStore store = new AiMemoryStore();
        AiPairMemory a = store.touch(ALICE, PLAYER_A);
        a.recordTurn(4);
        a.remember(note("A helped me defend the village.", AiImportance.HIGH), AiSentiment.STRONGLY_POSITIVE, 4, 12);
        AiPairMemory b = store.touch(ALICE, PLAYER_B);
        b.recordTurn(6);
        b.remember(note("B mocked my hat.", AiImportance.LOW), AiSentiment.NEGATIVE, 6, 12);

        AiMemoryStore loaded = AiMemoryStore.load(store.save(new CompoundTag()));
        assertEquals(2, loaded.size());
        AiMemory memoryOfA = loaded.get(ALICE, PLAYER_A).orElseThrow().recall(4, 8).get(0);
        assertEquals("A helped me defend the village.", memoryOfA.text());
        assertEquals(AiImportance.HIGH, memoryOfA.importance());
        assertEquals(AiSentiment.STRONGLY_POSITIVE, memoryOfA.sentiment());
        assertEquals(4, loaded.get(ALICE, PLAYER_A).orElseThrow().lastTalkDay());
        assertEquals(1, loaded.get(ALICE, PLAYER_A).orElseThrow().turns());
        assertEquals("B mocked my hat.", loaded.get(ALICE, PLAYER_B).orElseThrow().recall(6, 8).get(0).text());
    }

    @Test
    void aDamagedFileLoadsWhatItCan() {
        CompoundTag tag = new AiMemoryStore().save(new CompoundTag());
        CompoundTag bad = new CompoundTag();
        bad.putString("v", "not a uuid");
        tag.getList("pairs", 10).add(bad);
        assertEquals(0, AiMemoryStore.load(tag).size());
        assertEquals(0, AiMemoryStore.load(new CompoundTag()).size());
    }

    @Test
    void pruneDropsFadedMemoriesAndLongSilentEmptyPairs() {
        AiMemoryStore store = new AiMemoryStore();
        AiPairMemory pair = store.touch(ALICE, PLAYER_A);
        pair.recordTurn(0);
        pair.remember(note("low", AiImportance.LOW), AiSentiment.NEUTRAL, 0, 12);
        assertTrue(store.prune(10) > 0);
        assertTrue(store.get(ALICE, PLAYER_A).isPresent(), "a recent pair is kept even with no memory");
        store.prune(100);
        assertTrue(store.get(ALICE, PLAYER_A).isEmpty());
    }

    @Test
    void villagersHoldingOpinionsAreCappedLeastRecentlyChangedFirst() {
        AiMemoryStore store = new AiMemoryStore(2);
        UUID carol = UUID.randomUUID();
        store.adjustOpinion(ALICE, carol, "Carol", "warmth", 1, "kind", 0);
        store.adjustOpinion(BOB, carol, "Carol", "warmth", 1, "kind", 0);
        store.adjustOpinion(ALICE, carol, "Carol", "trust", 1, "honest", 1);
        UUID dan = UUID.randomUUID();
        store.adjustOpinion(dan, carol, "Carol", "warmth", 1, "kind", 1);
        assertTrue(store.opinions(BOB).isEmpty(), "the villager whose opinions changed longest ago goes");
        assertFalse(store.opinions(ALICE).isEmpty());
        assertFalse(store.opinions(dan).isEmpty());
    }
}
