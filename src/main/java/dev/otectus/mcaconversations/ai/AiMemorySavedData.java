package dev.otectus.mcaconversations.ai;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.List;
import java.util.UUID;

/**
 * {@code data/mcaconversations_ai_memory.dat}: the persistent half of AI conversations. Pinned to the
 * overworld like every other store of this mod, so a villager's memories follow the villager's UUID
 * across dimensions, chunk unloads and restarts. Server thread only.
 */
public final class AiMemorySavedData extends SavedData {

    private static final String DATA_NAME = "mcaconversations_ai_memory";

    private final AiMemoryStore store;

    private AiMemorySavedData(AiMemoryStore store) {
        this.store = store;
    }

    // 1.21.1 SavedData API, as ProgressSavedData uses it: a Factory, and a HolderLookup.Provider on load
    // and save. The payload is identical to the 1.20.1 Forge build's.
    public static AiMemorySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(AiMemorySavedData::create, AiMemorySavedData::load, null),
                DATA_NAME);
    }

    private static AiMemorySavedData create() {
        return new AiMemorySavedData(new AiMemoryStore());
    }

    private static AiMemorySavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        return new AiMemorySavedData(AiMemoryStore.load(tag));
    }

    /** Live memories of this pair for a prompt, most important first. Never null. */
    public List<AiMemory> recall(UUID villager, UUID player, long today, int limit) {
        return store.get(villager, player).map(pair -> pair.recall(today, limit)).orElse(List.of());
    }

    /** Turns this pair has had, and the day of the last one (-1 if never). */
    public long lastTalkDay(UUID villager, UUID player) {
        return store.get(villager, player).map(AiPairMemory::lastTalkDay).orElse(-1L);
    }

    public int turns(UUID villager, UUID player) {
        return store.get(villager, player).map(AiPairMemory::turns).orElse(0);
    }

    /** Books a completed turn and, when given, stores its memory. */
    public void recordTurn(UUID villager, UUID player, long day, AiMemoryNote note, AiSentiment sentiment, int cap) {
        AiPairMemory pair = store.touch(villager, player);
        pair.recordTurn(day);
        if (note != null) {
            pair.remember(note, sentiment, day, cap);
        }
        setDirty();
    }

    /** The pair's state for reading; empty when they have never had an AI conversation. */
    public java.util.Optional<AiPairMemory> peek(UUID villager, UUID player) {
        return store.get(villager, player);
    }

    /**
     * The pair's state for changing, created if needed. The caller mutates it on the server thread,
     * in the same tick; the file is marked dirty here.
     */
    public AiPairMemory edit(UUID villager, UUID player) {
        setDirty();
        return store.touch(villager, player);
    }

    public void recordBereavement(UUID villager, AiBereavement loss) {
        store.recordBereavement(villager, loss);
        setDirty();
    }

    public List<AiBereavement> bereavements(UUID villager, long today) {
        return store.bereavements(villager, today);
    }

    public List<AiNeighbourOpinion> opinions(UUID villager) {
        return store.opinions(villager);
    }

    public AiNeighbourOpinion adjustOpinion(UUID villager, UUID target, String targetName, String axis, int delta,
                                            String cause, long today) {
        setDirty();
        return store.adjustOpinion(villager, target, targetName, axis, delta, cause, today);
    }

    public void removeVillager(UUID villager) {
        if (store.removeVillager(villager) > 0) {
            setDirty();
        }
    }

    public void prune(long today) {
        if (store.prune(today) > 0) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        return store.save(tag);
    }
}
