package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.conversation.RelationshipBand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiActionsTest {

    private static final AiPolicy ALL = new AiPolicy(true, true, 0.6, 12);

    private static List<AiEffect> parse(String effects) {
        return AiReplyParser.parse("{\"message\": \"Claro.\", \"effects\": [" + effects + "]}").orElseThrow().effects();
    }

    private static AiTurnFacts facts(Set<String> actions, Set<String> chores, boolean grudge) {
        return new AiTurnFacts(RelationshipBand.FRIEND, 40, false, false, grudge, Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), 0, false, actions, chores);
    }

    private static AiReply reply(AiEffect... effects) {
        return new AiReply("Claro.", "", AiSentiment.NEUTRAL, 0.2, AiEmotion.NEUTRAL, Optional.empty(), List.of(effects), true);
    }

    @Test
    void ordersParseIntoTypedActions() {
        assertEquals(List.of(new AiEffect.Action(AiActionKind.WORK, Optional.of(AiChore.CHOP), 20, "")),
                parse("{\"type\":\"action\",\"do\":\"work\",\"task\":\"chop\",\"amount\":20}"));
        assertEquals(List.of(new AiEffect.Action(AiActionKind.WORK, Optional.of(AiChore.MINE), 256, "")),
                parse("{\"type\":\"action\",\"do\":\"work\",\"task\":\"mining\",\"amount\":500}"),
                "a work total is capped at 256 (a group shares it)");
        assertEquals(List.of(new AiEffect.Action(AiActionKind.GIVE, Optional.empty(), 5, "minecraft:oak_log")),
                parse("{\"type\":\"action\",\"do\":\"give\",\"item\":\"oak_log\",\"amount\":5}"));
        assertEquals(List.of(new AiEffect.Action(AiActionKind.GO_HOME, Optional.empty(), 0, "")),
                parse("{\"type\":\"action\",\"do\":\"go home\"}"));
        assertTrue(parse("{\"type\":\"action\",\"do\":\"work\"}").isEmpty(), "work needs a task");
        assertEquals(AiIntent.ALL, ((AiEffect.Action) parse("{\"type\":\"action\",\"do\":\"give\"}").get(0)).item(),
                "give with no item means everything gathered");
        assertTrue(parse("{\"type\":\"action\",\"do\":\"set_house_on_fire\"}").isEmpty());
    }

    @Test
    void choreNamesAreUnderstoodHoweverTheyComeBack() {
        assertEquals(Optional.of(AiChore.CHOP), AiChore.byKey("chopping"));
        assertEquals(Optional.of(AiChore.MINE), AiChore.byKey("prospecting"));
        assertEquals(Optional.of(AiChore.MINE), AiChore.byKey("stone"));
        assertEquals("PROSPECT", AiChore.MINE.mcaChoreName());
        assertEquals("CHOP", AiChore.CHOP.mcaChoreName());
    }

    @Test
    void onlyOfferedActionsAndTasksAreTakenEvenWithoutAConfidentJudgement() {
        AiTurnFacts facts = facts(Set.of("trade", "work"), Set.of("chop"), false);
        AiOutcomePlan plan = AiOutcomePlan.of(reply(
                new AiEffect.Action(AiActionKind.TRADE, Optional.empty(), 0, ""),
                new AiEffect.Action(AiActionKind.WORK, Optional.of(AiChore.MINE), 0, ""),
                new AiEffect.Action(AiActionKind.INVENTORY, Optional.empty(), 0, "")), ALL, facts);
        assertEquals(List.of(AiActionKind.TRADE), plan.actions().stream().map(AiEffect.Action::kind).toList(),
                "mine was not offered as a task, inventory not as an action");
        AiOutcomePlan chop = AiOutcomePlan.of(reply(new AiEffect.Action(AiActionKind.WORK, Optional.of(AiChore.CHOP), 10, "")),
                ALL, facts);
        assertEquals(1, chop.actions().size());
    }

    @Test
    void aVillagerHoldingAGrudgeOnlyLeaves() {
        AiTurnFacts facts = facts(Set.of("trade", "move", "follow", "stop_work"), Set.of(), true);
        AiOutcomePlan plan = AiOutcomePlan.of(reply(
                new AiEffect.Action(AiActionKind.FOLLOW, Optional.empty(), 0, ""),
                new AiEffect.Action(AiActionKind.MOVE, Optional.empty(), 0, "")), ALL, facts);
        assertEquals(List.of(AiActionKind.MOVE), plan.actions().stream().map(AiEffect.Action::kind).toList());
    }

    @Test
    void atMostTwoActionsAndNoDuplicates() {
        AiTurnFacts facts = facts(Set.of("trade", "follow", "armor"), Set.of(), false);
        AiOutcomePlan plan = AiOutcomePlan.of(reply(
                new AiEffect.Action(AiActionKind.TRADE, Optional.empty(), 0, ""),
                new AiEffect.Action(AiActionKind.TRADE, Optional.empty(), 0, ""),
                new AiEffect.Action(AiActionKind.FOLLOW, Optional.empty(), 0, ""),
                new AiEffect.Action(AiActionKind.ARMOR, Optional.empty(), 0, "")), ALL, facts);
        assertEquals(List.of(AiActionKind.TRADE, AiActionKind.FOLLOW), plan.actions().stream().map(AiEffect.Action::kind).toList());
    }

    @Test
    void gameplaySwitchedOffMeansNoActions() {
        AiTurnFacts facts = facts(Set.of("trade"), Set.of(), false);
        assertTrue(AiOutcomePlan.of(reply(new AiEffect.Action(AiActionKind.TRADE, Optional.empty(), 0, "")),
                new AiPolicy(true, false, 0.6, 12), facts).actions().isEmpty());
    }

    @Test
    void errandsCarryTheirPlaceOrItem() {
        assertEquals(List.of(new AiEffect.Action(AiActionKind.GUIDE, Optional.empty(), 0, "", "blacksmith")),
                parse("{\"type\":\"action\",\"do\":\"guide\",\"place\":\"blacksmith\"}"));
        assertEquals(List.of(new AiEffect.Action(AiActionKind.FETCH, Optional.empty(), 10, "minecraft:bread", "")),
                parse("{\"type\":\"action\",\"do\":\"fetch\",\"item\":\"bread\",\"amount\":10}"));
        assertTrue(parse("{\"type\":\"action\",\"do\":\"guide\"}").isEmpty(), "guide needs a place");
        assertTrue(parse("{\"type\":\"action\",\"do\":\"fetch\"}").isEmpty(), "fetch needs an item");
        assertEquals(1, parse("{\"type\":\"action\",\"do\":\"pick_up\"}").size());
    }

    @Test
    void aGuideOnlyGoesToAPlaceItWasShown() {
        AiTurnFacts facts = new AiTurnFacts(RelationshipBand.FRIEND, 40, false, false, false, Set.of(), Set.of(),
                Set.of("blacksmith"), Set.of(), Set.of(), 0, false, Set.of("guide", "wait_at"), Set.of());
        assertEquals(1, AiOutcomePlan.of(reply(new AiEffect.Action(AiActionKind.GUIDE, Optional.empty(), 0, "", "blacksmith")),
                ALL, facts).actions().size());
        assertTrue(AiOutcomePlan.of(reply(new AiEffect.Action(AiActionKind.GUIDE, Optional.empty(), 0, "", "castle")),
                ALL, facts).actions().isEmpty());
    }

    @Test
    void aGiftIsWelcomeEvenFromSomeoneInDisgrace() {
        AiTurnFacts facts = facts(Set.of("gift", "trade"), Set.of(), true);
        assertEquals(List.of(AiActionKind.GIFT), AiOutcomePlan.of(reply(
                new AiEffect.Action(AiActionKind.TRADE, Optional.empty(), 0, ""),
                new AiEffect.Action(AiActionKind.GIFT, Optional.empty(), 0, "")), ALL, facts)
                .actions().stream().map(AiEffect.Action::kind).toList());
    }

    @Test
    void helpersParseByNameOrEveryone() {
        AiEffect.Action named = (AiEffect.Action) parse("{\"type\":\"action\",\"do\":\"work\",\"task\":\"chop\","
                + "\"amount\":30,\"helpers\":[\"Bob\",\"Mara\"]}").get(0);
        assertEquals(List.of("Bob", "Mara"), named.helpers());
        assertEquals(30, named.amount());
        AiEffect.Action all = (AiEffect.Action) parse("{\"type\":\"action\",\"do\":\"follow\",\"helpers\":\"todos\"}").get(0);
        assertTrue(all.everyone());
    }

    @Test
    void onlyWillingVillagersAndGroupableActionsGetHelpers() {
        AiTurnFacts facts = new AiTurnFacts(RelationshipBand.FRIEND, 40, false, false, false, Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), 0, false, Set.of("work", "trade", "follow"), Set.of("chop"), Set.of("Bob"));
        AiOutcomePlan plan = AiOutcomePlan.of(reply(
                new AiEffect.Action(AiActionKind.WORK, Optional.of(AiChore.CHOP), 30, "", "", List.of("bob", "Zed")),
                new AiEffect.Action(AiActionKind.TRADE, Optional.empty(), 0, "", "", List.of("Bob"))), ALL, facts);
        assertEquals(List.of("bob"), plan.actions().get(0).helpers(), "Zed was not shown as willing");
        assertTrue(plan.actions().get(1).helpers().isEmpty(), "trading is not a group action");

        AiTurnFacts alone = facts(Set.of("follow"), Set.of(), false);
        assertTrue(AiOutcomePlan.of(reply(new AiEffect.Action(AiActionKind.FOLLOW, Optional.empty(), 0, "", "",
                List.of(AiEffect.Action.ALL))), ALL, alone).actions().get(0).helpers().isEmpty(), "nobody to bring in");
    }

    @Test
    void aGroupTotalIsSharedEvenly() {
        assertEquals(List.of(10, 10, 10), List.of(AiWork.share(30, 3, 0), AiWork.share(30, 3, 1), AiWork.share(30, 3, 2)));
        assertEquals(List.of(4, 3, 3), List.of(AiWork.share(10, 3, 0), AiWork.share(10, 3, 1), AiWork.share(10, 3, 2)));
        assertEquals(0, AiWork.share(0, 3, 0), "open-ended stays open-ended");
    }
}
