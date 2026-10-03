package dev.otectus.mcaconversations.ai;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything one villager carries about one player from AI conversations: a bounded set of memories
 * and two counters. Bounded by construction: {@link #remember} evicts before it can grow past the
 * configured cap, and no path stores raw dialogue.
 */
public final class AiPairMemory {

    /** No configuration can raise a pair's memory past this. */
    public static final int HARD_MAX_MEMORIES = 32;

    /** Least important first, then oldest: the order memories are given up in. */
    private static final Comparator<AiMemory> EVICTION_ORDER =
            Comparator.comparingInt((AiMemory m) -> m.importance().weight()).thenComparingLong(AiMemory::day);
    /** Most important first, then newest: the order memories are recalled in. */
    private static final Comparator<AiMemory> RECALL_ORDER = EVICTION_ORDER.reversed();

    private final List<AiMemory> memories = new ArrayList<>();
    private long lastTalkDay = -1;
    private int turns;

    /**
     * Stores a memory. A memory saying the same thing as one already held reinforces it instead (newer
     * day, the higher importance of the two) so repeating oneself cannot fill a villager's memory.
     * Expired memories are dropped first, then the least important oldest ones until within {@code cap}.
     *
     * @return true when the stored set changed
     */
    public boolean remember(AiMemoryNote note, AiSentiment sentiment, long day, int cap) {
        int limit = Math.max(0, Math.min(HARD_MAX_MEMORIES, cap));
        if (note == null || limit == 0) {
            return false;
        }
        String text = AiText.clean(note.text(), AiText.MAX_MEMORY);
        if (text.isEmpty()) {
            return false;
        }
        String fingerprint = AiText.fingerprint(text);
        AiImportance importance = note.importance();
        for (int i = 0; i < memories.size(); i++) {
            AiMemory held = memories.get(i);
            if (AiText.fingerprint(held.text()).equals(fingerprint)) {
                if (held.importance().weight() > importance.weight()) {
                    importance = held.importance();
                }
                memories.remove(i);
                break;
            }
        }
        memories.add(new AiMemory(text, importance, sentiment == null ? AiSentiment.NEUTRAL : sentiment, day));
        forgetExpired(day);
        while (memories.size() > limit) {
            memories.remove(memories.stream().min(EVICTION_ORDER).orElseThrow());
        }
        return true;
    }

    /** Drops memories past their importance's retention. Returns how many went. */
    public int forgetExpired(long today) {
        int before = memories.size();
        memories.removeIf(memory -> memory.expired(today));
        return before - memories.size();
    }

    /** Up to {@code limit} live memories, most important and most recent first. */
    public List<AiMemory> recall(long today, int limit) {
        return memories.stream().filter(m -> !m.expired(today)).sorted(RECALL_ORDER)
                .limit(Math.max(0, limit)).toList();
    }

    public void recordTurn(long day) {
        lastTalkDay = day;
        turns = turns == Integer.MAX_VALUE ? turns : turns + 1;
    }

    public long lastTalkDay() {
        return lastTalkDay;
    }

    public int turns() {
        return turns;
    }

    public int size() {
        return memories.size();
    }

    public boolean isEmpty() {
        return memories.isEmpty() && turns == 0;
    }

    CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (AiMemory memory : memories) {
            list.add(memory.toNbt());
        }
        tag.put("mem", list);
        tag.putLong("last", lastTalkDay);
        tag.putInt("turns", turns);
        return tag;
    }

    static AiPairMemory fromNbt(CompoundTag tag) {
        AiPairMemory pair = new AiPairMemory();
        ListTag list = tag.getList("mem", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && pair.memories.size() < HARD_MAX_MEMORIES; i++) {
            AiMemory.fromNbt(list.getCompound(i)).ifPresent(pair.memories::add);
        }
        pair.lastTalkDay = tag.contains("last") ? tag.getLong("last") : -1;
        pair.turns = Math.max(0, tag.getInt("turns"));
        return pair;
    }
}
