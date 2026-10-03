package dev.otectus.mcaconversations.ai;

import net.minecraft.nbt.CompoundTag;

import java.util.Optional;
import java.util.UUID;

/**
 * What one villager thinks of one neighbour, moved by what players tell them. Each axis runs -3..3,
 * the scale this mod's social opinions use; {@code cause} is the last reason, in the villager's words.
 */
public record AiNeighbourOpinion(UUID target, String name, int warmth, int trust, int respect, String cause, long day) {

    public static final int LIMIT = 3;

    public AiNeighbourOpinion adjust(String axis, int delta, String newCause, long today) {
        int w = warmth;
        int t = trust;
        int r = respect;
        switch (axis) {
            case "warmth" -> w = clamp(w + delta);
            case "trust" -> t = clamp(t + delta);
            case "respect" -> r = clamp(r + delta);
            default -> {
                return this;
            }
        }
        return new AiNeighbourOpinion(target, name, w, t, r, newCause == null || newCause.isEmpty() ? cause : newCause, today);
    }

    public int value(String axis) {
        return switch (axis) {
            case "warmth" -> warmth;
            case "trust" -> trust;
            case "respect" -> respect;
            default -> 0;
        };
    }

    public boolean neutral() {
        return warmth == 0 && trust == 0 && respect == 0;
    }

    private static int clamp(int value) {
        return Math.max(-LIMIT, Math.min(LIMIT, value));
    }

    CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("t", target);
        tag.putString("name", name);
        tag.putInt("w", warmth);
        tag.putInt("tr", trust);
        tag.putInt("r", respect);
        tag.putString("cause", cause);
        tag.putLong("day", day);
        return tag;
    }

    static Optional<AiNeighbourOpinion> fromNbt(CompoundTag tag) {
        if (!tag.hasUUID("t")) {
            return Optional.empty();
        }
        return Optional.of(new AiNeighbourOpinion(tag.getUUID("t"), AiText.clean(tag.getString("name"), 64),
                clamp(tag.getInt("w")), clamp(tag.getInt("tr")), clamp(tag.getInt("r")),
                AiText.clean(tag.getString("cause"), AiText.MAX_MEMORY), tag.getLong("day")));
    }
}
