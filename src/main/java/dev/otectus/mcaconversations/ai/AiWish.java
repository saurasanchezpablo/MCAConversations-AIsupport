package dev.otectus.mcaconversations.ai;

import net.minecraft.nbt.CompoundTag;

import java.util.Optional;

/** An item the villager let slip they would love, open until {@code expiresDay}. */
public record AiWish(String item, long createdDay, long expiresDay, String summary) {

    public boolean open(long today) {
        return today <= expiresDay;
    }

    CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("item", item);
        tag.putLong("made", createdDay);
        tag.putLong("until", expiresDay);
        tag.putString("text", summary);
        return tag;
    }

    static Optional<AiWish> fromNbt(CompoundTag tag) {
        String item = tag.getString("item");
        return item.isEmpty() ? Optional.empty()
                : Optional.of(new AiWish(item, tag.getLong("made"), tag.getLong("until"),
                AiText.clean(tag.getString("text"), AiText.MAX_MEMORY)));
    }
}
