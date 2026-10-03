package dev.otectus.mcaconversations.ai;

import net.minecraft.nbt.CompoundTag;

import java.util.Optional;

/**
 * One thing a villager remembers about one player from an AI conversation: a single short sentence
 * from the villager's point of view, never a transcript.
 *
 * @param day the Minecraft day it was formed or last reinforced
 */
public record AiMemory(String text, AiImportance importance, AiSentiment sentiment, long day) {

    public boolean expired(long today) {
        int retention = importance.retentionDays();
        return retention > 0 && today - day > retention;
    }

    CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("t", text);
        tag.putString("i", importance.key());
        tag.putString("s", sentiment.key());
        tag.putLong("d", day);
        return tag;
    }

    /** Empty for an entry that does not read back cleanly; a bad entry is dropped, never repaired. */
    static Optional<AiMemory> fromNbt(CompoundTag tag) {
        String text = AiText.clean(tag.getString("t"), AiText.MAX_MEMORY);
        Optional<AiImportance> importance = AiImportance.byKey(tag.getString("i"));
        if (text.isEmpty() || importance.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AiMemory(text, importance.get(),
                AiSentiment.byKey(tag.getString("s")).orElse(AiSentiment.NEUTRAL), tag.getLong("d")));
    }
}
