package dev.otectus.mcaconversations.ai;

/**
 * The server's switches for what an AI conversation may change, captured once per turn so a config
 * reload mid-request cannot apply half a turn under one policy and half under another.
 *
 * @param relationshipEffects whether replies may move hearts at all
 * @param gameplayEffects     whether replies may leave states, nudge dispositions or run MCA commands
 * @param minConfidence       the least confidence a judgement needs before it has any effect
 * @param memoriesPerPair     how many memories one villager keeps of one player; 0 disables memory
 */
public record AiPolicy(boolean relationshipEffects, boolean gameplayEffects, double minConfidence,
                       int memoriesPerPair) {

    public AiPolicy {
        minConfidence = Double.isNaN(minConfidence) ? 1 : Math.max(0, Math.min(1, minConfidence));
        memoriesPerPair = Math.max(0, Math.min(AiPairMemory.HARD_MAX_MEMORIES, memoriesPerPair));
    }
}
