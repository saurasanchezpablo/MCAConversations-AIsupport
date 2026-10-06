package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.conversation.AgeGroup;
import dev.otectus.mcaconversations.conversation.RelationshipBand;
import dev.otectus.mcaconversations.voice.VoiceIntent;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Words and deeds agree. Before a villager's line is spoken, this decides what runs from it and checks
 * what they said against what happened.
 *
 * <ul>
 *   <li>What they commit to is carried out: the model's own actions, a yes to what the player asked,
 *       and first-person promises in the line ("voy a talar", "te sigo"). Each still has to be possible
 *       for this villager now.</li>
 *   <li>What they refuse is not done: a refusal runs nothing on their behalf except leaving or stopping.</li>
 *   <li>What they committed to but could not do (no tool, nothing to give, a grudge, no furnace nearby)
 *       makes the line be rewritten before anyone hears it, so the villager says what is true ({@link AiRepair}).</li>
 * </ul>
 */
final class AiConsistency {

    /** What runs, and what the villager is held to. */
    record Decision(List<AiEffect.Action> run, Map<AiActionKind, AiCommitment.Claim> claimed, boolean refused) {
        static final Decision NONE = new Decision(List.of(), Map.of(), false);
    }

    /** Leaving and stopping are not favours: a villager who says no may still do those. */
    static final java.util.Set<AiActionKind> EVEN_WHEN_REFUSING = java.util.EnumSet.of(AiActionKind.STOP_WORK,
            AiActionKind.MOVE, AiActionKind.GO_HOME);
    static final int MAX_RUN = AiOutcomePlan.MAX_ACTIONS + 1;

    private AiConsistency() {
    }

    /**
     * Pure: the same message, reply, plan and facts always give the same decision.
     *
     * <p>The model's own reading of the exchange decides when there is one ({@link AiUnderstanding}: what
     * was asked, in any language, and what the villager answered). Only a reply without it (an endpoint
     * that ignores the format, and no second reading to be had) falls back to recognising common Spanish
     * and English phrasing.
     */
    static Decision decide(String message, AiReply reply, AiOutcomePlan plan, AiTurnFacts facts) {
        return reply.understanding().isPresent() ? understood(reply, reply.understanding().get(), plan, facts)
                : guessed(message, reply, plan, facts);
    }

    /** Decides from the model's reading of what was asked and answered. Pure. */
    static Decision understood(AiReply reply, AiUnderstanding reading, AiOutcomePlan plan, AiTurnFacts facts) {
        Optional<AiActionKind> asked = reading.asked();
        boolean refused = reading.refused() || (asked.isPresent() && reading.answer() != AiUnderstanding.Answer.YES
                && reply.deliveryOrDefault().intent() == VoiceIntent.REFUSE);
        boolean later = reading.answer() == AiUnderstanding.Answer.LATER;
        Map<AiActionKind, AiCommitment.Claim> claimed = new LinkedHashMap<>();

        // 1. Actions the model attached to the line, whether or not the game allowed them; a request put
        //    off to another day is not one of them.
        for (AiEffect effect : reply.effects()) {
            if (effect instanceof AiEffect.Action a && !(later && asked.equals(Optional.of(a.kind())))) {
                claimed.putIfAbsent(a.kind(), new AiCommitment.Claim(a.kind(), a.chore()));
            }
        }
        // 2. A yes to what the player asked. Being handed a gift needs no yes: the window opens unless the
        //    villager turns it down. A request whose details the game could not read (work with no task) is
        //    not held against the villager: nobody can tell what they agreed to.
        boolean takesIt = asked.isPresent() && !refused && !later && (reading.agreed()
                || asked.get() == AiActionKind.GIFT);
        if (takesIt && (reading.request().isPresent() || !needsDetail(asked.get()))) {
            claimed.putIfAbsent(asked.get(), new AiCommitment.Claim(asked.get(),
                    reading.request().flatMap(AiEffect.Action::chore)));
        }

        if (refused) {
            return refusal(plan, claimed);
        }
        List<AiEffect.Action> run = new ArrayList<>(plan.actions().stream()
                .filter(a -> !(later && asked.equals(Optional.of(a.kind())))).toList());
        if (takesIt && run.size() < MAX_RUN && run.stream().noneMatch(a -> a.kind() == asked.get())) {
            AiEffect.Action wanted = reading.request().orElseGet(() -> new AiEffect.Action(asked.get(), Optional.empty(), 0,
                    asked.get() == AiActionKind.GIVE ? AiIntent.ALL : "", "", List.of()));
            boolean grudge = facts.grudge() && !plan.forgive();
            if (wanted.kind() != AiActionKind.WORK || wanted.chore().isPresent()) {
                AiOutcomePlan.allowed(wanted, facts, grudge || plan.grudge()).ifPresent(run::add);
            }
        }
        return new Decision(List.copyOf(run), claimed, false);
    }

    /** A villager who said no: only leaving and stopping still happen, and they are held to nothing else. */
    private static Decision refusal(AiOutcomePlan plan, Map<AiActionKind, AiCommitment.Claim> claimed) {
        List<AiEffect.Action> run = plan.actions().stream().filter(a -> EVEN_WHEN_REFUSING.contains(a.kind())).toList();
        Map<AiActionKind, AiCommitment.Claim> kept = new LinkedHashMap<>();
        claimed.forEach((k, c) -> {
            if (EVEN_WHEN_REFUSING.contains(k)) {
                kept.put(k, c);
            }
        });
        return new Decision(run, kept, true);
    }

    /**
     * The fallback for a reply that did not say what was asked or answered: the player's request and the
     * villager's commitments are recognised from common Spanish and English phrasing. Pure.
     */
    static Decision guessed(String message, AiReply reply, AiOutcomePlan plan, AiTurnFacts facts) {
        String line = reply.dialogue();
        boolean refused = reply.deliveryOrDefault().intent() == VoiceIntent.REFUSE || AiCommitment.refuses(line);
        Optional<AiIntent.Intent> asked = AiIntent.detect(message);
        Map<AiActionKind, AiCommitment.Claim> said = AiCommitment.claims(line);
        Map<AiActionKind, AiCommitment.Claim> claimed = new LinkedHashMap<>();

        // 1. Actions the model attached to the line, whether or not the game allowed them.
        for (AiEffect effect : reply.effects()) {
            if (effect instanceof AiEffect.Action a) {
                claimed.putIfAbsent(a.kind(), new AiCommitment.Claim(a.kind(), a.chore()));
            }
        }
        // 2. A yes to what the player plainly asked.
        // (Being handed a gift needs no yes: the window opens unless the villager turns it down.)
        if (asked.isPresent() && !refused && !reply.sentiment().negative()
                && (AiCommitment.accepts(line) || said.containsKey(asked.get().kind()) || !plan.actions().isEmpty()
                || asked.get().kind() == AiActionKind.GIFT)) {
            claimed.putIfAbsent(asked.get().kind(), new AiCommitment.Claim(asked.get().kind(), asked.get().chore()));
        }
        // 3. First-person promises in the line, when the player was asking for something.
        if (asked.isPresent() || looksLikeRequest(message)) {
            said.forEach(claimed::putIfAbsent);
        }

        if (refused) {
            return refusal(plan, claimed);
        }

        List<AiEffect.Action> run = new ArrayList<>(plan.actions());
        for (AiCommitment.Claim claim : claimed.values()) {
            if (run.size() >= MAX_RUN || run.stream().anyMatch(a -> a.kind() == claim.kind())) {
                continue;
            }
            AiEffect.Action action;
            if (asked.isPresent() && asked.get().kind() == claim.kind()) {
                AiIntent.Intent want = asked.get();
                List<String> helpers = want.everyone() && AiOutcomePlan.GROUP_ACTIONS.contains(want.kind())
                        && !facts.helpers().isEmpty() ? List.of(AiEffect.Action.ALL) : List.of();
                action = new AiEffect.Action(want.kind(), want.chore().or(claim::chore), want.amount(), want.item(), "",
                        helpers);
            } else {
                // The model's own action that the plan held back stays held back; only spoken promises are added.
                boolean modelAsked = reply.effects().stream().anyMatch(e -> e instanceof AiEffect.Action a
                        && a.kind() == claim.kind());
                if (modelAsked || needsDetail(claim.kind())) {
                    continue;
                }
                action = new AiEffect.Action(claim.kind(), claim.chore(), 0,
                        claim.kind() == AiActionKind.GIVE ? AiIntent.ALL : "", "", List.of());
            }
            boolean grudge = (facts.grudge() && !plan.forgive()) || plan.grudge();
            AiOutcomePlan.allowed(action, facts, grudge).ifPresent(run::add);
        }
        return new Decision(List.copyOf(run), claimed, false);
    }

    /** Actions that cannot be carried out from a promise alone (they need a place or a plan). */
    private static boolean needsDetail(AiActionKind kind) {
        return kind == AiActionKind.GUIDE || kind == AiActionKind.WAIT_AT || kind == AiActionKind.FETCH
                || kind == AiActionKind.DATE || kind == AiActionKind.BUILD;
    }

    static boolean looksLikeRequest(String message) {
        String text = AiIntent.normalise(message);
        return message != null && text.matches(".*\\b(puedes|podrias|quieres|me ayudas|ayudame|necesito que|te pido|"
                + "could you|can you|would you|will you|please|por favor|help me|i need you)\\b.*");
    }

    /** The same rules the plan applies to the model's actions. Pure. */
    static boolean offered(AiTurnFacts facts, AiOutcomePlan plan, AiEffect.Action action) {
        boolean grudge = (facts.grudge() && !plan.forgive()) || plan.grudge();
        return AiOutcomePlan.allowed(action, facts, grudge).isPresent();
    }

    /** What the villager committed to but did not do, and why. */
    static List<AiActions.Issue> issues(Decision decision, List<AiActions.Result> results, AiTurnFacts facts,
                                        String playerName, Entity villager) {
        List<AiActions.Issue> out = new ArrayList<>();
        for (AiCommitment.Claim claim : decision.claimed().values()) {
            Optional<AiActions.Result> result = results.stream().filter(r -> r.kind() == claim.kind()).findFirst();
            if (result.isPresent()) {
                if (!result.get().done() && result.get().issue() != null) {
                    out.add(result.get().issue());
                }
                continue;
            }
            out.add(notPossible(claim, facts, playerName, villager));
        }
        return out;
    }

    /**
     * The villager says they were given something. A tool must really be in their hands or bag; anything
     * else must really have been handed over in the last minute ({@code recentlyReceived}).
     *
     * @param hasTool whether the villager holds the tool for that task now
     */
    static Optional<AiActions.Issue> receipt(String line, java.util.function.Predicate<AiChore> hasTool,
                                             boolean recentlyReceived, String playerName) {
        Optional<AiCommitment.Received> claim = AiCommitment.received(line);
        if (claim.isEmpty()) {
            return Optional.empty();
        }
        Optional<AiChore> tool = claim.get().tool();
        if (tool.isPresent()) {
            if (hasTool.test(tool.get())) {
                return Optional.empty();
            }
            return Optional.of(new AiActions.Issue(playerName + " has NOT given you " + tool.get().tool()
                    + ": you still do not have one. Do not thank them for it; if you need it, ask for it",
                    AiLines.variant("work_no_tool", net.minecraft.network.chat.Component.translatable(
                            "mcaconversations.ai.tool." + tool.get().key()))));
        }
        if (recentlyReceived) {
            return Optional.empty();
        }
        return Optional.of(new AiActions.Issue(playerName + " has not given you anything just now; do not thank them for "
                + "a gift you did not receive", AiLines.variant("cannot.generic")));
    }

    /**
     * As {@link #receipt(String, java.util.function.Predicate, boolean, String)}, from the model's own
     * reading of its line when it gave one ({@code received}), in any language; otherwise from the line's
     * Spanish or English wording.
     *
     * @param holds   whether the villager has that item now
     * @param toolFor the task an item is the tool for, if it is one
     */
    static Optional<AiActions.Issue> receipt(AiReply reply, java.util.function.Predicate<String> holds,
                                             java.util.function.Function<String, Optional<AiChore>> toolFor,
                                             java.util.function.Predicate<AiChore> hasTool, boolean recentlyReceived,
                                             String playerName) {
        if (reply.understanding().isEmpty()) {
            return receipt(reply.dialogue(), hasTool, recentlyReceived, playerName);
        }
        Optional<String> received = reply.understanding().get().received();
        if (received.isEmpty() || recentlyReceived) {
            return Optional.empty();
        }
        String item = received.get();
        if (!item.equals(AiUnderstanding.SOMETHING)) {
            Optional<AiChore> tool = toolFor.apply(item);
            if (holds.test(item) || tool.map(hasTool::test).orElse(false)) {
                return Optional.empty(); // they do have it: perhaps lent earlier
            }
            if (tool.isPresent()) {
                return Optional.of(new AiActions.Issue(playerName + " has NOT given you " + tool.get().tool()
                        + ": you still do not have one. Do not thank them for it; if you need it, ask for it",
                        AiLines.variant("work_no_tool", net.minecraft.network.chat.Component.translatable(
                                "mcaconversations.ai.tool." + tool.get().key()))));
            }
            return Optional.of(new AiActions.Issue(playerName + " has not given you " + AiContextFormat.words(item)
                    + "; you do not have it. Do not thank them for it", AiLines.variant("cannot.generic")));
        }
        return Optional.of(new AiActions.Issue(playerName + " has not given you anything just now; do not thank them for "
                + "a gift you did not receive", AiLines.variant("cannot.generic")));
    }

    /** Why an action the villager committed to was not one they could take. */
    static AiActions.Issue notPossible(AiCommitment.Claim claim, AiTurnFacts facts, String playerName, Entity villager) {
        if (facts.grudge()) {
            return issue("you are hurt and refuse " + playerName + " any favours until they make amends", "cannot.grudge");
        }
        AgeGroup age = villager == null ? AgeGroup.ADULT : McaCompat.ageGroup(villager);
        if (age == AgeGroup.CHILD || age == AgeGroup.TODDLER || age == AgeGroup.BABY) {
            return issue("you are only a child; you cannot do that", "cannot.young");
        }
        if (facts.band() == RelationshipBand.HOSTILE || facts.band() == RelationshipBand.TENSE) {
            return issue("you do not want to do anything for " + playerName, "cannot.grudge");
        }
        return switch (claim.kind()) {
            case TRADE -> issue("you are not a trader and have nothing to sell", "cannot.not_trader");
            case ARMOR, INVENTORY -> issue("you do not trust " + playerName + " enough for that yet", "cannot.trust");
            case DATE -> issue("a date with " + playerName + " is out of the question for you", "cannot.not_romance");
            case GIVE -> issue("you are not carrying anything you gathered to give", "give_nothing");
            case PICK_UP -> issue("there is nothing lying on the ground nearby", "cannot.nothing_here");
            case STORE -> issue("you carry nothing to store, or there is no chest nearby", "cannot.nothing_here");
            case FETCH -> issue("there is no chest nearby with that in it", "cannot.nothing_here");
            case BREED -> issue("there are no animals nearby that you have food for", "cannot.nothing_here");
            case COOK -> issue("there is no furnace, smoker or blast furnace nearby", "cannot.nothing_here");
            case GUIDE, WAIT_AT -> issue("you do not know a place like that in the village", "cannot.generic");
            default -> issue("that is not something you can do right now", "cannot.generic");
        };
    }

    private static AiActions.Issue issue(String why, String key) {
        return new AiActions.Issue(why, AiLines.variant(key));
    }
}
