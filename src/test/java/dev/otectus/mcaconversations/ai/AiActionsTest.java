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
        assertEquals(List.of(new AiEffect.Action(AiActionKind.WORK, Optional.of(AiChore.MINE), 64, "")),
                parse("{\"type\":\"action\",\"do\":\"work\",\"task\":\"mining\",\"amount\":500}"));
        assertEquals(List.of(new AiEffect.Action(AiActionKind.GIVE, Optional.empty(), 5, "minecraft:oak_log")),
                parse("{\"type\":\"action\",\"do\":\"give\",\"item\":\"oak_log\",\"amount\":5}"));
        assertEquals(List.of(new AiEffect.Action(AiActionKind.GO_HOME, Optional.empty(), 0, "")),
                parse("{\"type\":\"action\",\"do\":\"go home\"}"));
        assertTrue(parse("{\"type\":\"action\",\"do\":\"work\"}").isEmpty(), "work needs a task");
        assertTrue(parse("{\"type\":\"action\",\"do\":\"give\"}").isEmpty(), "give needs an item");
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
}
