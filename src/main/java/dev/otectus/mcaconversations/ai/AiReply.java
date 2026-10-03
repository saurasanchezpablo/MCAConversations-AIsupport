package dev.otectus.mcaconversations.ai;

import java.util.List;
import java.util.Optional;

/**
 * One model reply, parsed and validated: the line the villager says, kept apart from every effect the
 * model asked for. Nothing in here has been applied yet, and nothing in here can be applied without
 * passing {@link AiOutcomePlan} and {@link AiOutcomeApplier}.
 *
 * @param dialogue   what the villager says, already sanitised; never blank
 * @param command    an MCA chat-AI command id, or empty; MCA's own allow-list decides whether it runs
 * @param sentiment  the model's judgement of the exchange
 * @param confidence the model's confidence in that judgement, clamped to [0, 1]
 * @param emotion    the emotion the villager shows
 * @param memory     what the villager should remember, if anything
 * @param effects    the gameplay effects requested, already typed and bounded
 * @param structured false when the reply was not valid structured output and only the line survived;
 *                   an unstructured reply never has effects
 */
public record AiReply(String dialogue, String command, AiSentiment sentiment, double confidence,
                      AiEmotion emotion, Optional<AiMemoryNote> memory, List<AiEffect> effects,
                      boolean structured) {

    public AiReply {
        command = command == null ? "" : command;
        confidence = Double.isNaN(confidence) ? 0 : Math.max(0, Math.min(1, confidence));
        memory = memory == null ? Optional.empty() : memory;
        effects = effects == null ? List.of() : List.copyOf(effects);
    }

    /** A reply that is only a line: no judgement, no memory, no effects. */
    public static AiReply dialogueOnly(String dialogue) {
        return new AiReply(dialogue, "", AiSentiment.NEUTRAL, 0, AiEmotion.NEUTRAL, Optional.empty(),
                List.of(), false);
    }
}
