package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaChatAi;
import dev.otectus.mcaconversations.conversation.ConversationOutcomes;
import dev.otectus.mcaconversations.conversation.ConversationSession;
import dev.otectus.mcaconversations.conversation.DepthClass;
import dev.otectus.mcaconversations.conversation.Relationships;
import dev.otectus.mcaconversations.disposition.DispositionApply;
import dev.otectus.mcaconversations.disposition.Dispositions;
import dev.otectus.mcaconversations.progress.AffectionApply;
import dev.otectus.mcaconversations.progress.AffectionContext;
import dev.otectus.mcaconversations.progress.AffectionMath;
import dev.otectus.mcaconversations.progress.AffectionOutcome;
import dev.otectus.mcaconversations.progress.ProgressSavedData;
import dev.otectus.mcaconversations.progress.ReplayPolicy;
import dev.otectus.mcaconversations.state.StateTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.Set;

/**
 * Applies an {@link AiOutcomePlan} to the world, on the server thread, through the systems that
 * already own each kind of change. Nothing here invents a parallel mechanic:
 * <ul>
 *   <li>hearts go through {@link ProgressSavedData#applyAffection} (idempotency, scaling, diminishing
 *       returns, per-conversation and per-day budgets) and then MCA's own {@code rewardHearts}
 *       (particles, fatigue, the hearts advancement, mood, sensitive villagers' doubled negatives);</li>
 *   <li>lingering moods are {@link StateTracker} states authored dialogue already reads;</li>
 *   <li>disposition nudges go through {@link Dispositions#apply} and its farming guard;</li>
 *   <li>commands go through MCA's own allow-list lookup.</li>
 * </ul>
 * Each step is isolated: a failure in one is logged at debug and does not stop the others, and none
 * of them can be reached with a value the plan did not already bound.
 */
final class AiOutcomeApplier {

    /** Every AI conversation is budgeted as a standard-depth conversation (4 up / 5 down per conversation). */
    static final DepthClass BUDGET = DepthClass.STANDARD;
    /** The disposition farming guard's topic key, so repeated AI nudges of an axis diminish within a day. */
    static final String DISPOSITION_TOPIC = "ai_chat";

    /** What actually happened, for the debug log. */
    record Applied(int grantedHearts, int measuredHearts, AffectionOutcome.Reason heartReason, boolean stateLeft,
                   boolean dispositionsRequested, boolean remembered, boolean commandRan) {
    }

    private AiOutcomeApplier() {
    }

    static Applied apply(MinecraftServer server, Entity villager, ServerPlayer player, AiReply reply, AiOutcomePlan plan,
                         AiPolicy policy, AiSessions.Session session, Set<String> offeredCommands, long now, long day) {
        int granted = 0;
        int measured = 0;
        AffectionOutcome.Reason reason = AffectionOutcome.Reason.ZERO;
        if (plan.authoredHearts() != 0) {
            try {
                AffectionApply directive = new AffectionApply(plan.decision(),
                        AffectionMath.clampAuthored(plan.authoredHearts()), Optional.of(BUDGET), ReplayPolicy.DAILY_REPEAT);
                AffectionContext context = new AffectionContext(BUDGET,
                        session.positiveApplied(), session.negativeApplied(),
                        McaConversationsConfig.conversationDailyPositiveCap(),
                        McaConversationsConfig.conversationDailyNegativeCap(),
                        McaConversationsConfig.strongerNegativeOutcomes(),
                        McaConversationsConfig.conversationHeartMultiplier(),
                        // Unique per turn: the store's idempotency guard refuses a replayed transaction.
                        plan.decision() + "@" + now + "#" + session.nextSerial(),
                        now);
                AffectionOutcome outcome = ProgressSavedData.get(server)
                        .applyAffection(villager.getUUID(), player.getUUID(), directive, context);
                reason = outcome.reason();
                granted = outcome.granted();
                if (granted != 0) {
                    measured = McaCompat.rewardHearts(villager, player, granted);
                    session.recordApplied(granted);
                    ConversationOutcomes.markHeartChangeNow(villager, measured);
                }
            } catch (Throwable t) {
                McaConversations.LOGGER.debug("AI heart change failed; no hearts moved", t);
            }
        }

        boolean stateLeft = false;
        if (plan.state().isPresent()) {
            try {
                StateTracker.apply(villager, player, plan.state().get());
                stateLeft = true;
            } catch (Throwable t) {
                McaConversations.LOGGER.debug("AI lingering state failed", t);
            }
        }

        plan.reaction().ifPresent(semantic -> {
            try {
                ConversationOutcomes.react(villager, player, semantic, ConversationSession.Frontend.CHAT);
            } catch (Throwable t) {
                McaConversations.LOGGER.debug("AI reaction failed", t);
            }
        });

        boolean dispositions = false;
        if (!plan.dispositions().isEmpty()) {
            try {
                Dispositions.apply(villager, player, new DispositionApply(DISPOSITION_TOPIC, plan.dispositions()));
                dispositions = true;
            } catch (Throwable t) {
                McaConversations.LOGGER.debug("AI disposition nudge failed", t);
            }
        }

        // An AI conversation is real contact: it counts toward the lived friendship the relationship
        // bands require, exactly as a scripted conversation does (credited at most once per day there).
        Relationships.creditContact(villager, player);

        boolean remembered = false;
        try {
            AiMemorySavedData.get(server).recordTurn(villager.getUUID(), player.getUUID(), day,
                    plan.memory().orElse(null), reply.sentiment(), policy.memoriesPerPair());
            remembered = plan.memory().isPresent();
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("AI memory write failed", t);
        }

        boolean commandRan = false;
        if (!plan.command().isEmpty() && offeredCommands.contains(plan.command())) {
            commandRan = McaChatAi.runCommand(plan.command(), villager, player);
        }
        return new Applied(granted, measured, reason, stateLeft, dispositions, remembered, commandRan);
    }
}
