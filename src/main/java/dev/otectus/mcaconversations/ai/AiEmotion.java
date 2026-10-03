package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.conversation.ReactionSemantic;
import dev.otectus.mcaconversations.state.ConversationState;

import java.util.Locale;
import java.util.Optional;

/**
 * The emotion the villager shows in reply. A closed vocabulary: the model picks one, and each maps
 * to systems this mod already has rather than to anything new: a player-scoped
 * {@link ConversationState} that authored dialogue already reacts to, and a heart-neutral
 * {@link ReactionSemantic} that plays when Townstead and Emotecraft are present.
 */
public enum AiEmotion {
    NEUTRAL(null, null),
    HAPPY(null, ReactionSemantic.WARM),
    GRATEFUL(ConversationState.GRATEFUL, ReactionSemantic.GRATEFUL),
    PROUD(ConversationState.PROUD, ReactionSemantic.WARM),
    AMUSED(null, ReactionSemantic.AMUSED),
    SURPRISED(null, ReactionSemantic.ACKNOWLEDGE),
    SAD(null, ReactionSemantic.HURT),
    HURT(ConversationState.ANNOYED, ReactionSemantic.HURT),
    ANNOYED(ConversationState.ANNOYED, ReactionSemantic.REBUFF),
    ANGRY(ConversationState.ANNOYED, ReactionSemantic.REBUFF),
    AFRAID(null, ReactionSemantic.AWKWARD);

    private final ConversationState state;
    private final ReactionSemantic reaction;

    AiEmotion(ConversationState state, ReactionSemantic reaction) {
        this.state = state;
        this.reaction = reaction;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The lingering state this emotion leaves on the villager toward this player. Only the emotions
     * that should outlast the exchange have one; it is applied only when the sentiment agrees with it
     * (see {@link AiOutcomePlan}), so a model that says "grateful" about an insult changes nothing.
     */
    public Optional<ConversationState> state() {
        return Optional.ofNullable(state);
    }

    public Optional<ReactionSemantic> reaction() {
        return Optional.ofNullable(reaction);
    }

    /** True when this emotion's state is one a positive exchange would leave. */
    public boolean warm() {
        return this == GRATEFUL || this == PROUD || this == HAPPY;
    }

    /** True when this emotion's state is one a negative exchange would leave. */
    public boolean cold() {
        return this == HURT || this == ANNOYED || this == ANGRY;
    }

    public static Optional<AiEmotion> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (AiEmotion emotion : values()) {
            if (emotion.key().equals(key)) {
                return Optional.of(emotion);
            }
        }
        return Optional.empty();
    }
}
