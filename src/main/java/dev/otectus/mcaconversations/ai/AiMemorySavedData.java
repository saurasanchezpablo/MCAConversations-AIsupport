package dev.otectus.mcaconversations.ai;

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

    public static AiMemorySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                tag -> new AiMemorySavedData(AiMemoryStore.load(tag)),
                () -> new AiMemorySavedData(new AiMemoryStore()),
                DATA_NAME);
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
    public CompoundTag save(CompoundTag tag) {
        return store.save(tag);
    }
}
