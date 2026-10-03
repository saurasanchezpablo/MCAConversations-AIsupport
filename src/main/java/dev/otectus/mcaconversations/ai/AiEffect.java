package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.disposition.DispositionAxis;

/**
 * One gameplay effect the model <em>requested</em>. A request only: every effect type is parsed by
 * {@link AiReplyParser} into a typed record with bounded parameters, and {@link AiOutcomeApplier}
 * still decides on the server whether it is legal right now. A type the parser does not know is
 * dropped, so the model can never reach an effect this mod did not write.
 *
 * <p>Adding a type: a record here, a parse branch in {@link AiReplyParser#parseEffect}, a line in the
 * schema {@link AiPromptBuilder} describes, and an apply branch in {@link AiOutcomeApplier}.
 */
public sealed interface AiEffect permits AiEffect.DispositionNudge {

    /** The stable key the model uses for this effect type. */
    String type();

    /**
     * Moves one axis of the villager's disposition vector toward the player by one step. The model
     * picks the axis and the direction; the step size, the daily cap and the anti-farming guard are
     * the disposition system's own ({@code Dispositions.apply} through {@code FarmingGuard}).
     *
     * @param direction {@code +1} or {@code -1}
     */
    record DispositionNudge(DispositionAxis axis, int direction) implements AiEffect {
        public static final String TYPE = "disposition";

        public DispositionNudge {
            direction = Integer.signum(direction);
        }

        @Override
        public String type() {
            return TYPE;
        }
    }
}
