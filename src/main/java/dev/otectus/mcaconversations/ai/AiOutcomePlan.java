package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.conversation.ReactionSemantic;
import dev.otectus.mcaconversations.disposition.DispositionAxis;
import dev.otectus.mcaconversations.state.ConversationState;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * What one validated reply is allowed to change, decided before anything is touched. Pure: the same
 * reply under the same {@link AiPolicy} always yields the same plan, which is what the tests pin.
 *
 * <p>This is where "the model requests, the game decides" happens:
 * <ul>
 *   <li>Hearts come from the sentiment band ({@link AiSentiment#baseHearts}), and only when the model
 *       is confident enough. The plan holds the <em>authored</em> delta; the per-conversation budget,
 *       the daily caps, diminishing returns and MCA's own personality doubling are applied later by
 *       the same guard chain authored dialogue uses.</li>
 *   <li>A lingering state is left only when the emotion agrees with the judgement: "grateful" after
 *       an exchange judged negative leaves nothing.</li>
 *   <li>A disposition nudge must point the way the judgement does (trust, respect and warmth rise
 *       with a positive exchange and fall with a negative one; tension the reverse). A neutral
 *       exchange nudges nothing.</li>
 *   <li>An unstructured reply plans nothing at all but its line.</li>
 * </ul>
 *
 * @param decision        the anti-farming decision id the heart change is booked under
 * @param authoredHearts  the heart delta before guards; 0 for none
 * @param state           the lingering state to leave on the villager toward this player
 * @param reaction        the heart-neutral reaction to play
 * @param dispositions    the disposition deltas to request, by axis
 * @param memory          what to remember
 * @param command         the MCA command to try, or empty
 */
public record AiOutcomePlan(String decision, int authoredHearts, Optional<ConversationState> state,
                            Optional<ReactionSemantic> reaction, Map<DispositionAxis, Integer> dispositions,
                            Optional<AiMemoryNote> memory, String command) {

    public static final String DECISION_PREFIX = "ai.chat.";
    /** Disposition step for one nudge; a strong judgement moves the axis a little further. */
    static final int DISPOSITION_STEP = 2;
    static final int STRONG_DISPOSITION_STEP = 3;

    public static AiOutcomePlan of(AiReply reply, AiPolicy policy) {
        AiSentiment sentiment = reply.sentiment();
        String decision = DECISION_PREFIX + sentiment.key();
        if (!reply.structured()) {
            return new AiOutcomePlan(decision, 0, Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), "");
        }
        boolean confident = reply.confidence() >= policy.minConfidence();

        int hearts = policy.relationshipEffects() && confident ? sentiment.baseHearts() : 0;

        Optional<ConversationState> state = Optional.empty();
        if (policy.gameplayEffects() && confident
                && ((reply.emotion().warm() && sentiment.positive()) || (reply.emotion().cold() && sentiment.negative()))) {
            state = reply.emotion().state();
        }

        Map<DispositionAxis, Integer> dispositions = new EnumMap<>(DispositionAxis.class);
        if (policy.gameplayEffects() && confident && sentiment != AiSentiment.NEUTRAL) {
            int sign = sentiment.positive() ? 1 : -1;
            int step = sentiment == AiSentiment.STRONGLY_POSITIVE || sentiment == AiSentiment.STRONGLY_NEGATIVE
                    ? STRONG_DISPOSITION_STEP : DISPOSITION_STEP;
            for (AiEffect effect : reply.effects()) {
                if (effect instanceof AiEffect.DispositionNudge nudge) {
                    int expected = nudge.axis() == DispositionAxis.TENSION ? -sign : sign;
                    if (nudge.direction() == expected) {
                        dispositions.putIfAbsent(nudge.axis(), expected * step);
                    }
                }
            }
        }

        Optional<AiMemoryNote> memory = policy.memoriesPerPair() > 0 ? reply.memory() : Optional.empty();
        String command = policy.gameplayEffects() ? reply.command() : "";
        return new AiOutcomePlan(decision, hearts, state, reply.emotion().reaction(),
                Collections.unmodifiableMap(dispositions), memory, command);
    }
}
