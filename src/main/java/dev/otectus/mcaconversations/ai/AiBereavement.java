package dev.otectus.mcaconversations.ai;

import net.minecraft.nbt.CompoundTag;

import java.util.Optional;

/**
 * A villager lost someone close: who, how they were related, and when. Kept for a few weeks so the
 * loss colours how the villager talks and how much a kind or cruel word matters.
 *
 * @param relation from the bereaved villager's side: partner, child, parent or sibling
 */
public record AiBereavement(String name, String relation, long day) {

    /** How long a loss is fresh enough to mention and to weigh on the villager. */
    public static final long MOURNING_DAYS = 21;

    public boolean fresh(long today) {
        return today - day <= MOURNING_DAYS;
    }

    CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putString("rel", relation);
        tag.putLong("day", day);
        return tag;
    }

    static Optional<AiBereavement> fromNbt(CompoundTag tag) {
        String name = AiText.clean(tag.getString("name"), 64);
        String relation = tag.getString("rel");
        return name.isEmpty() || relation.isEmpty() ? Optional.empty()
                : Optional.of(new AiBereavement(name, relation, tag.getLong("day")));
    }
}
