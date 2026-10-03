package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.conversation.ConversationOutcomes;
import dev.otectus.mcaconversations.conversation.DepthClass;
import dev.otectus.mcaconversations.progress.AffectionApply;
import dev.otectus.mcaconversations.progress.AffectionContext;
import dev.otectus.mcaconversations.progress.AffectionMath;
import dev.otectus.mcaconversations.progress.AffectionOutcome;
import dev.otectus.mcaconversations.progress.ProgressSavedData;
import dev.otectus.mcaconversations.progress.ReplayPolicy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

/**
 * The one way AI conversations move hearts: through the guard chain authored dialogue uses
 * ({@link ProgressSavedData#applyAffection}: idempotency, scaling, diminishing returns, conversation
 * and daily budgets) and then MCA's own {@code rewardHearts}. Server thread only.
 */
final class AiHearts {

    /** What happened: granted by the guard chain, and measured after MCA's own adjustments. */
    record Grant(int granted, int measured, AffectionOutcome.Reason reason) {
        static final Grant NONE = new Grant(0, 0, AffectionOutcome.Reason.ZERO);
    }

    private AiHearts() {
    }

    /**
     * @param sessionPositive hearts this conversation already paid (0 outside a conversation)
     * @param transactionId   unique per event; a replayed id is refused by the store
     */
    static Grant grant(MinecraftServer server, Entity villager, ServerPlayer player, String decision, int delta,
                       DepthClass budget, ReplayPolicy policy, int sessionPositive, int sessionNegative,
                       String transactionId, long now) {
        if (delta == 0) {
            return Grant.NONE;
        }
        try {
            AffectionApply directive = new AffectionApply(decision, AffectionMath.clampAuthored(delta),
                    Optional.of(budget), policy);
            AffectionContext context = new AffectionContext(budget, sessionPositive, sessionNegative,
                    McaConversationsConfig.conversationDailyPositiveCap(),
                    McaConversationsConfig.conversationDailyNegativeCap(),
                    McaConversationsConfig.strongerNegativeOutcomes(),
                    McaConversationsConfig.conversationHeartMultiplier(),
                    transactionId, now);
            AffectionOutcome outcome = ProgressSavedData.get(server)
                    .applyAffection(villager.getUUID(), player.getUUID(), directive, context);
            int measured = 0;
            if (outcome.granted() != 0) {
                measured = McaCompat.rewardHearts(villager, player, outcome.granted());
                ConversationOutcomes.markHeartChangeNow(villager, measured);
            }
            return new Grant(outcome.granted(), measured, outcome.reason());
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("AI heart change failed; no hearts moved", t);
            return Grant.NONE;
        }
    }
}
