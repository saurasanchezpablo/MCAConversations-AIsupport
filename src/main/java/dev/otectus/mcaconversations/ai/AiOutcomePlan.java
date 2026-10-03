package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.conversation.ReactionSemantic;
import dev.otectus.mcaconversations.conversation.RelationshipBand;
import dev.otectus.mcaconversations.disposition.DispositionAxis;
import dev.otectus.mcaconversations.state.ConversationState;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What one validated reply is allowed to change, decided before anything is touched. Pure: the same
 * reply under the same {@link AiPolicy} and {@link AiTurnFacts} always yields the same plan.
 *
 * <p>The model requests, the game decides:
 * <ul>
 *   <li>Hearts come from the sentiment band, only when the model is confident; a grieving villager
 *       takes cruelty harder. The guard chain authored dialogue uses is applied later.</li>
 *   <li>A lingering state is left only when the emotion agrees with the judgement; a romantic one
 *       only where romance is allowed.</li>
 *   <li>Disposition nudges must point the way the judgement does; attraction only where romance is
 *       allowed and MCA's courtship threshold is met.</li>
 *   <li>Quests, topics, places, neighbours and bystanders must be ones the model was shown.</li>
 *   <li>A villager holding a grudge does the player no favours until they are forgiven.</li>
 * </ul>
 */
public record AiOutcomePlan(String decision, int authoredHearts, Optional<ConversationState> state,
                            Optional<ReactionSemantic> reaction, Map<DispositionAxis, Integer> dispositions,
                            Optional<AiMemoryNote> memory, String command,
                            Optional<AiEffect.Promise> promise, Optional<AiEffect.Wish> wish,
                            Optional<String> questOffer, Optional<String> unlockTopic,
                            Optional<AiEffect.Opinion> opinion, Optional<String> directions,
                            int tradeMood, boolean grudge, boolean forgive,
                            Optional<AiInterjection> interjection, Optional<GossipTone> gossip,
                            List<AiEffect.Action> actions) {

    public static final String DECISION_PREFIX = "ai.chat.";
    /** Disposition step for one nudge; a strong judgement moves the axis a little further. */
    static final int DISPOSITION_STEP = 2;
    static final int STRONG_DISPOSITION_STEP = 3;
    /** Vanilla trade reputation points (MINOR_POSITIVE / MINOR_NEGATIVE) per exchange. */
    static final int TRADE_BONUS = 10;
    static final int TRADE_PENALTY = 5;
    static final int STRONG_TRADE_PENALTY = 10;
    /** MCA commands a villager holding a grudge still obeys: being told to leave is not a favour. */
    static final Set<String> COMMANDS_DESPITE_GRUDGE = Set.of("move-freely", "try-go-home");

    /** Actions a villager holding a grudge still takes: leaving is not a favour. */
    static final Set<AiActionKind> ACTIONS_DESPITE_GRUDGE = EnumSet.of(AiActionKind.MOVE, AiActionKind.GO_HOME,
            AiActionKind.STOP_WORK);
    /** Most actions one reply may carry (e.g. "here's an axe" and "go chop"). */
    static final int MAX_ACTIONS = 2;

    /** How a strongly felt exchange is told around the village. */
    public enum GossipTone { KIND, CRUEL }

    public static AiOutcomePlan of(AiReply reply, AiPolicy policy) {
        return of(reply, policy, AiTurnFacts.none());
    }

    public static AiOutcomePlan of(AiReply reply, AiPolicy policy, AiTurnFacts facts) {
        AiSentiment sentiment = reply.sentiment();
        String decision = DECISION_PREFIX + sentiment.key();
        if (!reply.structured()) {
            return new AiOutcomePlan(decision, 0, Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), "",
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), 0, false, false, Optional.empty(), Optional.empty(), List.of());
        }
        boolean confident = reply.confidence() >= policy.minConfidence();
        boolean gameplay = policy.gameplayEffects() && confident;
        boolean strong = sentiment == AiSentiment.STRONGLY_POSITIVE || sentiment == AiSentiment.STRONGLY_NEGATIVE;

        // --- hearts ---------------------------------------------------------------------------------
        int hearts = policy.relationshipEffects() && confident ? sentiment.baseHearts() : 0;
        if (hearts < 0 && facts.grieving()) {
            // Cruelty to someone in mourning cuts deeper: half as much again, rounded away from zero.
            hearts = -(int) Math.ceil(-hearts * 1.5);
        }

        // --- grudge and forgiveness -------------------------------------------------------------------
        boolean requestedForgive = reply.effects().stream().anyMatch(e -> e instanceof AiEffect.Forgive);
        boolean requestedGrudge = reply.effects().stream().anyMatch(e -> e instanceof AiEffect.Grudge);
        boolean forgive = gameplay && facts.grudge() && requestedForgive && sentiment.positive();
        boolean grudge = gameplay && !forgive
                && (sentiment == AiSentiment.STRONGLY_NEGATIVE || (requestedGrudge && sentiment.negative()));
        boolean holdsGrudge = (facts.grudge() && !forgive) || grudge;

        // --- lingering state --------------------------------------------------------------------------
        AiEmotion emotion = reply.emotion();
        boolean romanceOk = facts.romanceAllowed() && facts.hearts() >= AiTurnFacts.ROMANCE_MIN_HEARTS;
        Optional<ConversationState> state = Optional.empty();
        if (gameplay && ((emotion.warm() && sentiment.positive()) || (emotion.cold() && sentiment.negative()))
                && (emotion != AiEmotion.SMITTEN || romanceOk)) {
            state = emotion.state();
        }

        // --- dispositions -----------------------------------------------------------------------------
        Map<DispositionAxis, Integer> dispositions = new EnumMap<>(DispositionAxis.class);
        if (gameplay && sentiment != AiSentiment.NEUTRAL) {
            int sign = sentiment.positive() ? 1 : -1;
            int step = strong ? STRONG_DISPOSITION_STEP : DISPOSITION_STEP;
            for (AiEffect effect : reply.effects()) {
                if (effect instanceof AiEffect.DispositionNudge nudge) {
                    if (nudge.axis() == DispositionAxis.ATTRACTION && !romanceOk) {
                        continue; // no courtship with a child, a relative, someone else's partner, or a near stranger
                    }
                    int expected = nudge.axis() == DispositionAxis.TENSION ? -sign : sign;
                    if (nudge.direction() == expected) {
                        dispositions.putIfAbsent(nudge.axis(), expected * step);
                    }
                }
            }
            if (facts.grieving() && sentiment.positive() && emotion.warm()) {
                // Comfort offered to a grieving villager is remembered as trust.
                dispositions.putIfAbsent(DispositionAxis.TRUST, step);
            }
            if (forgive) {
                dispositions.putIfAbsent(DispositionAxis.TENSION, -step);
            }
        }

        // --- requested social and gameplay effects -----------------------------------------------------
        Optional<AiEffect.Promise> promise = Optional.empty();
        Optional<AiEffect.Wish> wish = Optional.empty();
        Optional<String> quest = Optional.empty();
        Optional<String> unlock = Optional.empty();
        Optional<AiEffect.Opinion> opinion = Optional.empty();
        Optional<String> directions = Optional.empty();
        boolean discount = false;
        if (gameplay) {
            for (AiEffect effect : reply.effects()) {
                if (effect instanceof AiEffect.Promise p && promise.isEmpty()
                        && facts.openPromises() < AiTurnFacts.MAX_OPEN_PROMISES) {
                    promise = Optional.of(p);
                } else if (effect instanceof AiEffect.Wish w && wish.isEmpty() && !facts.wishActive()
                        && !sentiment.negative() && facts.atLeast(RelationshipBand.ACQUAINTANCE)) {
                    wish = Optional.of(w);
                } else if (effect instanceof AiEffect.OfferQuest q && quest.isEmpty() && !holdsGrudge
                        && !sentiment.negative() && facts.offeredQuests().contains(q.questId())) {
                    quest = Optional.of(q.questId());
                } else if (effect instanceof AiEffect.UnlockTopic u && unlock.isEmpty() && sentiment.positive()
                        && !holdsGrudge && facts.offeredTopics().contains(u.topic())) {
                    unlock = Optional.of(u.topic());
                } else if (effect instanceof AiEffect.Opinion o && opinion.isEmpty()
                        && AiTurnFacts.contains(facts.neighbours(), o.about())) {
                    opinion = Optional.of(o);
                } else if (effect instanceof AiEffect.Directions d && directions.isEmpty() && !holdsGrudge
                        && !sentiment.negative() && facts.offeredPlaces().contains(d.place())) {
                    directions = Optional.of(d.place());
                } else if (effect instanceof AiEffect.Discount && sentiment.positive() && !holdsGrudge
                        && facts.atLeast(RelationshipBand.FRIEND)) {
                    discount = true;
                }
            }
        }
        int tradeMood = 0;
        if (gameplay) {
            if (discount) {
                tradeMood = TRADE_BONUS;
            } else if (sentiment.negative()) {
                tradeMood = -(strong ? STRONG_TRADE_PENALTY : TRADE_PENALTY);
            }
        }

        // --- memory, gossip, interjection, command ----------------------------------------------------
        Optional<AiMemoryNote> memory = policy.memoriesPerPair() > 0 ? reply.memory() : Optional.empty();
        Optional<GossipTone> gossip = Optional.empty();
        if (gameplay && strong && reply.memory().isPresent() && !reply.memory().get().secret()) {
            gossip = Optional.of(sentiment.positive() ? GossipTone.KIND : GossipTone.CRUEL);
        }
        Optional<AiInterjection> interjection = reply.interjection()
                .filter(i -> AiTurnFacts.contains(facts.bystanders(), i.speaker()));
        String command = policy.gameplayEffects() ? reply.command() : "";
        if (holdsGrudge && !COMMANDS_DESPITE_GRUDGE.contains(command)) {
            command = "";
        }
        Optional<ReactionSemantic> reaction = forgive ? Optional.of(ReactionSemantic.REPAIR) : emotion.reaction();

        // --- spoken actions -----------------------------------------------------------------------------
        // Asked for in so many words, so they do not wait on a confident judgement of feelings; they wait
        // on the game: only actions and tasks the villager was shown as possible right now are taken.
        List<AiEffect.Action> actions = new java.util.ArrayList<>();
        if (policy.gameplayEffects()) {
            for (AiEffect effect : reply.effects()) {
                if (!(effect instanceof AiEffect.Action action) || actions.size() >= MAX_ACTIONS) {
                    continue;
                }
                boolean allowed = facts.offeredActions().contains(action.kind().key())
                        && (action.kind() != AiActionKind.WORK
                        || action.chore().map(c -> facts.offeredChores().contains(c.key())).orElse(false))
                        && (!holdsGrudge || ACTIONS_DESPITE_GRUDGE.contains(action.kind()));
                if (allowed && actions.stream().noneMatch(a -> a.kind() == action.kind())) {
                    actions.add(action);
                }
            }
        }

        return new AiOutcomePlan(decision, hearts, state, reaction, Collections.unmodifiableMap(dispositions), memory,
                command, promise, wish, quest, unlock, opinion, directions, tradeMood, grudge, forgive, interjection,
                gossip, List.copyOf(actions));
    }
}
