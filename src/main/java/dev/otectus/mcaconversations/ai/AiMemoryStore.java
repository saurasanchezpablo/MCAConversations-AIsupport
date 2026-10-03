package dev.otectus.mcaconversations.ai;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Iterator;
import java.util.List;
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

    /** Most fresh losses remembered per villager. */
    static final int MAX_BEREAVEMENTS = 3;
    /** Most neighbours one villager holds an AI-shaped opinion of. */
    static final int MAX_OPINIONS = 8;

    /** Insertion order is write order: {@link #touch} moves a pair to the end. */
    private final LinkedHashMap<PairKey, AiPairMemory> pairs = new LinkedHashMap<>();
    /** Per villager, not per pair: a loss and an opinion of a neighbour belong to the villager. */
    private final LinkedHashMap<UUID, List<AiBereavement>> bereavements = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, List<AiNeighbourOpinion>> opinions = new LinkedHashMap<>();
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
        bereavements.remove(villager);
        opinions.remove(villager);
        // The dead are no longer anyone's neighbour to have opinions about.
        opinions.values().forEach(list -> list.removeIf(o -> o.target().equals(villager)));
        return before - pairs.size();
    }

    // --- bereavement -------------------------------------------------------------------------------------

    public void recordBereavement(UUID villager, AiBereavement loss) {
        List<AiBereavement> list = bereavements.computeIfAbsent(villager, k -> new java.util.ArrayList<>());
        list.add(loss);
        while (list.size() > MAX_BEREAVEMENTS) {
            list.remove(0);
        }
        while (bereavements.size() > maxPairs) {
            Iterator<UUID> oldest = bereavements.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    public List<AiBereavement> bereavements(UUID villager, long today) {
        return bereavements.getOrDefault(villager, List.of()).stream().filter(b -> b.fresh(today)).toList();
    }

    // --- opinions of neighbours ----------------------------------------------------------------------------

    public java.util.Optional<AiNeighbourOpinion> opinion(UUID villager, UUID target) {
        return opinions.getOrDefault(villager, List.of()).stream().filter(o -> o.target().equals(target)).findFirst();
    }

    public List<AiNeighbourOpinion> opinions(UUID villager) {
        return List.copyOf(opinions.getOrDefault(villager, List.of()));
    }

    /** Moves one axis of one opinion by {@code delta}, creating it; the least held opinion makes room. */
    public AiNeighbourOpinion adjustOpinion(UUID villager, UUID target, String targetName, String axis, int delta,
                                            String cause, long today) {
        List<AiNeighbourOpinion> list = opinions.computeIfAbsent(villager, k -> new java.util.ArrayList<>());
        AiNeighbourOpinion current = list.stream().filter(o -> o.target().equals(target)).findFirst()
                .orElse(new AiNeighbourOpinion(target, targetName, 0, 0, 0, "", today));
        list.remove(current);
        AiNeighbourOpinion next = current.adjust(axis, delta, cause, today);
        if (!next.neutral()) {
            list.add(next);
        }
        while (list.size() > MAX_OPINIONS) {
            list.remove(list.stream().min(java.util.Comparator.comparingInt((AiNeighbourOpinion o) ->
                    Math.abs(o.warmth()) + Math.abs(o.trust()) + Math.abs(o.respect()))
                    .thenComparingLong(AiNeighbourOpinion::day)).orElseThrow());
        }
        return next;
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
        for (List<AiBereavement> list : bereavements.values()) {
            int before = list.size();
            list.removeIf(b -> !b.fresh(today));
            changed += before - list.size();
        }
        bereavements.values().removeIf(List::isEmpty);
        int before = pairs.size();
        pairs.values().removeIf(pair -> pair.size() == 0 && pair.openPromises() == 0
                && today - pair.lastTalkDay() > AiImportance.MEDIUM.retentionDays());
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
        ListTag losses = new ListTag();
        bereavements.forEach((villager, entries) -> entries.forEach(entry -> {
            CompoundTag t = entry.toNbt();
            t.putUUID("v", villager);
            losses.add(t);
        }));
        tag.put("bereavements", losses);
        ListTag views = new ListTag();
        opinions.forEach((villager, entries) -> entries.forEach(entry -> {
            CompoundTag t = entry.toNbt();
            t.putUUID("v", villager);
            views.add(t);
        }));
        tag.put("opinions", views);
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
        ListTag losses = tag.getList("bereavements", Tag.TAG_COMPOUND);
        for (int i = 0; i < losses.size(); i++) {
            CompoundTag t = losses.getCompound(i);
            if (t.hasUUID("v")) {
                AiBereavement.fromNbt(t).ifPresent(b -> store.recordBereavement(t.getUUID("v"), b));
            }
        }
        ListTag views = tag.getList("opinions", Tag.TAG_COMPOUND);
        for (int i = 0; i < views.size(); i++) {
            CompoundTag t = views.getCompound(i);
            if (t.hasUUID("v")) {
                AiNeighbourOpinion.fromNbt(t).ifPresent(o -> {
                    List<AiNeighbourOpinion> held = store.opinions.computeIfAbsent(t.getUUID("v"), k -> new java.util.ArrayList<>());
                    if (held.size() < MAX_OPINIONS) {
                        held.add(o);
                    }
                });
            }
        }
        return store;
    }
}
