package dev.otectus.mcaconversations.ai;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.Optional;

/**
 * Something the player promised one villager. Item promises are settled by gifts (several gifts add
 * up to the promised count); a promise to come back is kept by talking to the villager on or after
 * the due day. A promise still open a day after it was due is broken.
 *
 * @param item   item id or {@code #tag}; empty for a promise to come back
 * @param count  how many items; 0 for a visit
 */
public record AiPromise(int id, String item, int count, int delivered, long createdDay, long dueDay, State state,
                        long settledDay, String summary) {

    /** Days past the due day before an open promise counts as broken. */
    public static final long GRACE_DAYS = 1;

    public enum State {
        PENDING, KEPT, BROKEN;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public boolean isVisit() {
        return item == null || item.isEmpty();
    }

    public boolean pending() {
        return state == State.PENDING;
    }

    public boolean overdue(long today) {
        return pending() && today > dueDay + GRACE_DAYS;
    }

    /** {@code n} more items handed over; kept once the count is reached. */
    public AiPromise deliver(int n, long today) {
        int total = Math.min(count, delivered + Math.max(0, n));
        return total >= count ? new AiPromise(id, item, count, total, createdDay, dueDay, State.KEPT, today, summary)
                : new AiPromise(id, item, count, total, createdDay, dueDay, state, settledDay, summary);
    }

    public AiPromise settle(State outcome, long today) {
        return new AiPromise(id, item, count, delivered, createdDay, dueDay, outcome, today, summary);
    }

    CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("item", item == null ? "" : item);
        tag.putInt("count", count);
        tag.putInt("got", delivered);
        tag.putLong("made", createdDay);
        tag.putLong("due", dueDay);
        tag.putString("state", state.key());
        tag.putLong("settled", settledDay);
        tag.putString("text", summary);
        return tag;
    }

    static Optional<AiPromise> fromNbt(CompoundTag tag) {
        State state;
        try {
            state = State.valueOf(tag.getString("state").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(new AiPromise(tag.getInt("id"), tag.getString("item"), Math.max(0, tag.getInt("count")),
                Math.max(0, tag.getInt("got")), tag.getLong("made"), tag.getLong("due"), state, tag.getLong("settled"),
                AiText.clean(tag.getString("text"), AiText.MAX_MEMORY)));
    }
}
