package dev.otectus.mcaconversations.ai;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AI-conversation memory for every villager-player pair, keyed by both UUIDs so Alice's memory of
 * one player can never be read for another player or written to Bob. Pure: no world, so it is tested
 * directly and wrapped by {@link AiMemorySavedData} for persistence.
 *
 * <p>Bounded twice: each pair by {@link AiPairMemory}'s cap, and the store by {@link #maxPairs},
 * evicting the pair least recently written (the same 4096 the progress and disposition stores use).
 */
public final class AiMemoryStore {

    public static final int DEFAULT_MAX_PAIRS = 4096;
    static final int FORMAT = 1;

    /** A villager and a player. The whole identity of a relationship. */
    public record PairKey(UUID villager, UUID player) {
    }

    /** Insertion order is write order: {@link #touch} moves a pair to the end. */
    private final LinkedHashMap<PairKey, AiPairMemory> pairs = new LinkedHashMap<>();
    private final int maxPairs;

    public AiMemoryStore() {
        this(DEFAULT_MAX_PAIRS);
    }

    public AiMemoryStore(int maxPairs) {
        this.maxPairs = Math.max(1, maxPairs);
    }

    public Optional<AiPairMemory> get(UUID villager, UUID player) {
        return Optional.ofNullable(pairs.get(new PairKey(villager, player)));
    }

    /** The pair's memory, created if needed, and marked most recently written. */
    public AiPairMemory touch(UUID villager, UUID player) {
        PairKey key = new PairKey(villager, player);
        AiPairMemory pair = pairs.remove(key);
        if (pair == null) {
            pair = new AiPairMemory();
        }
        pairs.put(key, pair);
        while (pairs.size() > maxPairs) {
            Iterator<PairKey> oldest = pairs.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return pair;
    }

    /** Forgets everything every player shared with this villager (it died). */
    public int removeVillager(UUID villager) {
        int before = pairs.size();
        pairs.keySet().removeIf(key -> key.villager().equals(villager));
        return before - pairs.size();
    }

    /**
     * Drops expired memories everywhere, then pairs that hold no memory and have not talked for a
     * month. Returns how many memories and pairs went, so a caller knows whether anything changed.
     */
    public int prune(long today) {
        int changed = 0;
        for (AiPairMemory pair : pairs.values()) {
            changed += pair.forgetExpired(today);
        }
        int before = pairs.size();
        pairs.values().removeIf(pair -> pair.size() == 0 && today - pair.lastTalkDay() > AiImportance.MEDIUM.retentionDays());
        return changed + before - pairs.size();
    }

    public int size() {
        return pairs.size();
    }

    public CompoundTag save(CompoundTag tag) {
        tag.putInt("version", FORMAT);
        ListTag list = new ListTag();
        for (Map.Entry<PairKey, AiPairMemory> entry : pairs.entrySet()) {
            CompoundTag pair = entry.getValue().toNbt();
            pair.putUUID("v", entry.getKey().villager());
            pair.putUUID("p", entry.getKey().player());
            list.add(pair);
        }
        tag.put("pairs", list);
        return tag;
    }

    /** Reads what it can; an entry without both UUIDs is skipped rather than failing the whole file. */
    public static AiMemoryStore load(CompoundTag tag) {
        AiMemoryStore store = new AiMemoryStore();
        ListTag list = tag.getList("pairs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag pair = list.getCompound(i);
            if (!pair.hasUUID("v") || !pair.hasUUID("p")) {
                continue;
            }
            store.pairs.put(new PairKey(pair.getUUID("v"), pair.getUUID("p")), AiPairMemory.fromNbt(pair));
        }
        while (store.pairs.size() > store.maxPairs) {
            Iterator<PairKey> oldest = store.pairs.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return store;
    }
}
