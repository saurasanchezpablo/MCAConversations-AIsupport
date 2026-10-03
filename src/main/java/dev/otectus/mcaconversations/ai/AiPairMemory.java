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

    /** Most promises (open and recently settled) kept per pair. */
    public static final int MAX_PROMISES = 6;
    /** Days a settled promise stays known, so the villager can still bring it up. */
    public static final long SETTLED_PROMISE_DAYS = 7;

    private final List<AiMemory> memories = new ArrayList<>();
    private final List<AiPromise> promises = new ArrayList<>();
    private AiWish wish;
    private long lastTalkDay = -1;
    private int turns;
    private int nextPromiseId = 1;
    /** The villager refuses this player favours until this game time (ticks); -1 for none. */
    private long grudgeUntil = -1;
    private String grudgeReason = "";
    private long lastDirectionsDay = -1;
    private long tradeMoodDay = -1;
    private int tradeMoodToday;

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

    // --- promises -------------------------------------------------------------------------------------

    public List<AiPromise> promises() {
        return List.copyOf(promises);
    }

    public long openPromises() {
        return promises.stream().filter(AiPromise::pending).count();
    }

    /** Records a new promise; returns it, or empty when there is no room for another open one. */
    public java.util.Optional<AiPromise> addPromise(AiEffect.Promise request, long today, int maxOpen) {
        if (openPromises() >= maxOpen) {
            return java.util.Optional.empty();
        }
        AiPromise promise = new AiPromise(nextPromiseId++, request.item(), request.isVisit() ? 0 : request.count(), 0,
                today, today + request.days(), AiPromise.State.PENDING, -1, request.summary());
        promises.add(promise);
        trimPromises(today);
        return java.util.Optional.of(promise);
    }

    /** Replaces a promise by id (after a delivery or a settlement). */
    public void updatePromise(AiPromise changed) {
        for (int i = 0; i < promises.size(); i++) {
            if (promises.get(i).id() == changed.id()) {
                promises.set(i, changed);
                return;
            }
        }
    }

    private void trimPromises(long today) {
        promises.removeIf(p -> !p.pending() && today - p.settledDay() > SETTLED_PROMISE_DAYS);
        while (promises.size() > MAX_PROMISES) {
            // Oldest settled first; an open promise is never dropped to make room.
            AiPromise victim = promises.stream().filter(p -> !p.pending()).findFirst().orElse(null);
            if (victim == null) {
                break;
            }
            promises.remove(victim);
        }
    }

    // --- wish ------------------------------------------------------------------------------------------

    public java.util.Optional<AiWish> wish(long today) {
        return wish != null && wish.open(today) ? java.util.Optional.of(wish) : java.util.Optional.empty();
    }

    public void setWish(AiWish wish) {
        this.wish = wish;
    }

    // --- grudge ----------------------------------------------------------------------------------------

    public boolean grudge(long gameTime) {
        return grudgeUntil >= 0 && gameTime < grudgeUntil;
    }

    public String grudgeReason() {
        return grudgeReason;
    }

    public void holdGrudge(long untilGameTime, String reason) {
        grudgeUntil = Math.max(grudgeUntil, untilGameTime);
        grudgeReason = reason == null ? "" : AiText.clean(reason, AiText.MAX_MEMORY);
    }

    public void forgive() {
        grudgeUntil = -1;
        grudgeReason = "";
    }

    // --- daily limits ------------------------------------------------------------------------------------

    /** True while no directions to a far place were given on {@code today}. */
    public boolean lastDirectionsDayIsNot(long today) {
        return lastDirectionsDay != today;
    }

    /** True once per day: the villager gives directions at most once a day to one player. */
    public boolean claimDirections(long today) {
        if (lastDirectionsDay == today) {
            return false;
        }
        lastDirectionsDay = today;
        return true;
    }

    /**
     * How much of {@code requested} trade-mood points may still be applied today, booking it. The
     * absolute total per pair per day is capped at {@code dailyCap}.
     */
    public int claimTradeMood(int requested, long today, int dailyCap) {
        if (tradeMoodDay != today) {
            tradeMoodDay = today;
            tradeMoodToday = 0;
        }
        int room = Math.max(0, dailyCap - tradeMoodToday);
        int granted = Integer.signum(requested) * Math.min(room, Math.abs(requested));
        tradeMoodToday += Math.abs(granted);
        return granted;
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
        return memories.isEmpty() && turns == 0 && promises.isEmpty() && wish == null && grudgeUntil < 0;
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
        ListTag promiseList = new ListTag();
        for (AiPromise promise : promises) {
            promiseList.add(promise.toNbt());
        }
        tag.put("promises", promiseList);
        tag.putInt("next_promise", nextPromiseId);
        if (wish != null) {
            tag.put("wish", wish.toNbt());
        }
        tag.putLong("grudge", grudgeUntil);
        tag.putString("grudge_reason", grudgeReason);
        tag.putLong("directions_day", lastDirectionsDay);
        tag.putLong("trade_day", tradeMoodDay);
        tag.putInt("trade_today", tradeMoodToday);
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
        ListTag promiseList = tag.getList("promises", Tag.TAG_COMPOUND);
        for (int i = 0; i < promiseList.size() && pair.promises.size() < MAX_PROMISES; i++) {
            AiPromise.fromNbt(promiseList.getCompound(i)).ifPresent(pair.promises::add);
        }
        pair.nextPromiseId = Math.max(1, tag.getInt("next_promise"));
        if (tag.contains("wish", Tag.TAG_COMPOUND)) {
            pair.wish = AiWish.fromNbt(tag.getCompound("wish")).orElse(null);
        }
        pair.grudgeUntil = tag.contains("grudge") ? tag.getLong("grudge") : -1;
        pair.grudgeReason = AiText.clean(tag.getString("grudge_reason"), AiText.MAX_MEMORY);
        pair.lastDirectionsDay = tag.contains("directions_day") ? tag.getLong("directions_day") : -1;
        pair.tradeMoodDay = tag.contains("trade_day") ? tag.getLong("trade_day") : -1;
        pair.tradeMoodToday = Math.max(0, tag.getInt("trade_today"));
        return pair;
    }
}
