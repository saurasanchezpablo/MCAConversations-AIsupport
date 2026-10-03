package dev.otectus.mcaconversations.ai;

import java.util.Locale;
import java.util.Optional;

/**
 * How long a villager keeps a memory of the player, and which memory goes first when a pair's memory
 * is full. Retention is in Minecraft days; {@code 0} means kept until evicted by something newer.
 */
public enum AiImportance {
    LOW(1, 7),
    MEDIUM(2, 30),
    HIGH(3, 0);

    private final int weight;
    private final int retentionDays;

    AiImportance(int weight, int retentionDays) {
        this.weight = weight;
        this.retentionDays = retentionDays;
    }

    public int weight() {
        return weight;
    }

    public int retentionDays() {
        return retentionDays;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<AiImportance> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (AiImportance importance : values()) {
            if (importance.key().equals(key)) {
                return Optional.of(importance);
            }
        }
        return Optional.empty();
    }
}
