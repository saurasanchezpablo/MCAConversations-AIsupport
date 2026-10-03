package dev.otectus.mcaconversations.ai;

import java.util.Locale;
import java.util.Optional;

/**
 * How one exchange changed the villager's feelings toward the player, as the model judged it. The
 * model only ever names a band; how many hearts a band is worth is the game's decision
 * ({@link AiRelationshipPolicy}), so no reply can name a number of hearts.
 */
public enum AiSentiment {
    STRONGLY_NEGATIVE(-4),
    NEGATIVE(-2),
    NEUTRAL(0),
    POSITIVE(1),
    STRONGLY_POSITIVE(3);

    /**
     * The authored heart delta before every guard. Sized against MCA's own interactions, which pay
     * +3 to +5 for a successful chat and -2 to -5 for a failed one: a warm remark is worth less than
     * a successful scripted interaction, and an insult stings more than a compliment pleases.
     */
    private final int baseHearts;

    AiSentiment(int baseHearts) {
        this.baseHearts = baseHearts;
    }

    public int baseHearts() {
        return baseHearts;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean positive() {
        return baseHearts > 0;
    }

    public boolean negative() {
        return baseHearts < 0;
    }

    /** Lenient on case, spaces and hyphens; anything unrecognised is empty, never a guess. */
    public static Optional<AiSentiment> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (AiSentiment sentiment : values()) {
            if (sentiment.key().equals(key)) {
                return Optional.of(sentiment);
            }
        }
        return Optional.empty();
    }
}
