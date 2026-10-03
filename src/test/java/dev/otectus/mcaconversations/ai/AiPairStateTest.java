package dev.otectus.mcaconversations.ai;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPairStateTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void itemPromisesAddUpAcrossGiftsAndAreKeptAtTheCount() {
        AiPairMemory pair = new AiPairMemory();
        AiPromise promise = pair.addPromise(new AiEffect.Promise("minecraft:wheat", 10, 2, "wheat"), 5, 3).orElseThrow();
        assertEquals(7, promise.dueDay());
        promise = promise.deliver(4, 6);
        assertTrue(promise.pending());
        promise = promise.deliver(8, 6);
        assertEquals(AiPromise.State.KEPT, promise.state());
        assertEquals(10, promise.delivered());
    }

    @Test
    void aPromiseIsBrokenOnlyAfterItsGraceDay() {
        AiPromise promise = new AiPairMemory().addPromise(new AiEffect.Promise("", 0, 2, "come back"), 0, 3).orElseThrow();
        assertFalse(promise.overdue(2));
        assertFalse(promise.overdue(3));
        assertTrue(promise.overdue(4));
    }

    @Test
    void openPromisesAreCappedAndSettledOnesFade() {
        AiPairMemory pair = new AiPairMemory();
        for (int i = 0; i < 3; i++) {
            assertTrue(pair.addPromise(new AiEffect.Promise("", 0, 1, "p" + i), 0, 3).isPresent());
        }
        assertTrue(pair.addPromise(new AiEffect.Promise("", 0, 1, "p3"), 0, 3).isEmpty());
        AiPromise first = pair.promises().get(0);
        pair.updatePromise(first.settle(AiPromise.State.KEPT, 1));
        assertEquals(2, pair.openPromises());
        pair.addPromise(new AiEffect.Promise("", 0, 1, "later"), 20, 3);
        assertTrue(pair.promises().stream().noneMatch(p -> p.id() == first.id()), "a settled promise fades after a week");
    }

    @Test
    void grudgesExpireAndAreForgiven() {
        AiPairMemory pair = new AiPairMemory();
        pair.holdGrudge(1000, "they insulted my mother");
        assertTrue(pair.grudge(999));
        assertFalse(pair.grudge(1000));
        pair.holdGrudge(5000, "again");
        pair.forgive();
        assertFalse(pair.grudge(10));
    }

    @Test
    void dailyLimitsHold() {
        AiPairMemory pair = new AiPairMemory();
        assertTrue(pair.claimDirections(3));
        assertFalse(pair.claimDirections(3));
        assertTrue(pair.claimDirections(4));
        assertEquals(10, pair.claimTradeMood(10, 1, 20));
        assertEquals(-10, pair.claimTradeMood(-10, 1, 20));
        assertEquals(0, pair.claimTradeMood(10, 1, 20), "twenty points a day, either way");
        assertEquals(10, pair.claimTradeMood(10, 2, 20));
    }

    @Test
    void everythingRoundTripsThroughNbt() {
        AiMemoryStore store = new AiMemoryStore();
        AiPairMemory pair = store.touch(ALICE, STEVE);
        pair.addPromise(new AiEffect.Promise("#minecraft:logs", 5, 3, "logs for the fence"), 2, 3);
        pair.setWish(new AiWish("minecraft:blue_orchid", 2, 9, "her mother's flower"));
        pair.holdGrudge(70_000, "called me a liar");
        pair.claimDirections(2);
        store.recordBereavement(ALICE, new AiBereavement("Tom", "sibling", 1));
        store.adjustOpinion(ALICE, BOB, "Bob", "trust", -1, "Steve says he steals", 2);

        AiMemoryStore loaded = AiMemoryStore.load(store.save(new CompoundTag()));
        AiPairMemory back = loaded.get(ALICE, STEVE).orElseThrow();
        AiPromise promise = back.promises().get(0);
        assertEquals("#minecraft:logs", promise.item());
        assertEquals(5, promise.count());
        assertEquals(5, promise.dueDay());
        assertEquals("logs for the fence", promise.summary());
        assertEquals("minecraft:blue_orchid", back.wish(5).orElseThrow().item());
        assertTrue(back.wish(10).isEmpty());
        assertTrue(back.grudge(60_000));
        assertEquals("called me a liar", back.grudgeReason());
        assertFalse(back.claimDirections(2));
        assertEquals("Tom", loaded.bereavements(ALICE, 5).get(0).name());
        assertEquals(-1, loaded.opinion(ALICE, BOB).orElseThrow().trust());
    }

    @Test
    void mourningFadesAndTheDeadLeaveEveryonesOpinions() {
        AiMemoryStore store = new AiMemoryStore();
        store.recordBereavement(ALICE, new AiBereavement("Tom", "sibling", 0));
        assertEquals(1, store.bereavements(ALICE, AiBereavement.MOURNING_DAYS).size());
        assertTrue(store.bereavements(ALICE, AiBereavement.MOURNING_DAYS + 1).isEmpty());

        store.adjustOpinion(ALICE, BOB, "Bob", "warmth", 1, "kind", 0);
        store.removeVillager(BOB);
        assertTrue(store.opinion(ALICE, BOB).isEmpty());
    }

    @Test
    void opinionsStayInRangeAndNeutralOnesAreDropped() {
        AiMemoryStore store = new AiMemoryStore();
        for (int i = 0; i < 10; i++) {
            store.adjustOpinion(ALICE, BOB, "Bob", "respect", 1, "brave", i);
        }
        assertEquals(AiNeighbourOpinion.LIMIT, store.opinion(ALICE, BOB).orElseThrow().respect());
        store.adjustOpinion(ALICE, UUID.randomUUID(), "Eve", "warmth", 1, "", 0);
        UUID eve = store.opinions(ALICE).stream().filter(o -> o.name().equals("Eve")).findFirst().orElseThrow().target();
        store.adjustOpinion(ALICE, eve, "Eve", "warmth", -1, "", 1);
        assertTrue(store.opinion(ALICE, eve).isEmpty());
    }
}
