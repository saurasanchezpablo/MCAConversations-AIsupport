package dev.otectus.mcaconversations.ai;

import com.google.gson.JsonObject;
import dev.otectus.mcaconversations.conversation.RelationshipBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure rules behind villagers' own lives: chatter, dates, childhood, skills, building, appearance. */
class AiLivesTest {

    private static final AiPolicy ALL = new AiPolicy(true, true, 0.6, 12);
    private static final long DAY = AiVillageEventType.DAY;

    // --- villagers talking among themselves ---------------------------------------------------------------

    @Test
    void anOverheardChatKeepsOnlyTheTwoSpeakers() {
        String reply = "Sure! {\"lines\": [{\"speaker\": \"ana\", \"text\": \"Did you see the sky last night?\"},"
                + "{\"speaker\": \"Luis\", \"text\": \"Red as a beet.\"}, {\"speaker\": \"Steve\", \"text\": \"hi\"},"
                + "{\"speaker\": \"Ana\", \"text\": \"\"}], \"topic\": \"the sky\", \"warmth\": 3}";
        AiSmallTalk.Exchange exchange = AiSmallTalk.parse(reply, "Ana", "Luis").orElseThrow();
        assertEquals(2, exchange.lines().size());
        assertEquals("Ana", exchange.lines().get(0).speaker(), "speaker names are matched ignoring case");
        assertEquals(1, exchange.warmth(), "warmth is a direction only");
        assertEquals("the sky", exchange.topic());
        assertTrue(AiSmallTalk.parse("{\"lines\": [{\"speaker\": \"Ana\", \"text\": \"Alone.\"}]}", "Ana", "Luis").isEmpty(),
                "one line is not a conversation");
        assertTrue(AiSmallTalk.parse("not json", "Ana", "Luis").isEmpty());
    }

    // --- dates ------------------------------------------------------------------------------------------------

    @Test
    void datesAreSetForNowTheEveningOrTomorrow() {
        long morning = 3 * DAY + 2_000;
        assertEquals(morning + 200, AiDates.startFor(0, morning));
        assertEquals(3 * DAY + 11_000, AiDates.startFor(1, morning));
        assertEquals(4 * DAY + 11_000, AiDates.startFor(2, morning));
        assertEquals(4 * DAY + 11_000, AiDates.startFor(1, 3 * DAY + 14_000), "the evening is over: tomorrow");
        assertEquals(3 * DAY + 11_700, AiDates.startFor(1, 3 * DAY + 11_500), "already evening: in a moment");
    }

    @Test
    void aDateIsJudgedByHowItWent() {
        assertEquals(AiDates.Outcome.LOVELY, AiDates.outcome(4, 3, 0));
        assertEquals(AiDates.Outcome.BAD, AiDates.outcome(3, 1, 2));
        assertEquals(AiDates.Outcome.AWKWARD, AiDates.outcome(1, 1, 0), "one line is not much of a date");
        assertEquals(AiDates.Outcome.AWKWARD, AiDates.outcome(0, 0, 0));
    }

    @Test
    void onADateEverythingCountsForMore() {
        AiTurnFacts dating = new AiTurnFacts(RelationshipBand.FRIEND, 40, true, false, false, Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), 0, false, Set.of(), Set.of(), Set.of(), Set.of(),
                new AiTurnFacts.Life(Set.of(), Set.of(), true));
        AiTurnFacts normal = new AiTurnFacts(RelationshipBand.FRIEND, 40, true, false, false, Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), 0, false);
        AiReply sweet = reply(AiSentiment.POSITIVE);
        AiReply rude = reply(AiSentiment.NEGATIVE);
        int base = AiOutcomePlan.of(sweet, ALL, normal).authoredHearts();
        assertTrue(AiOutcomePlan.of(sweet, ALL, dating).authoredHearts() > base);
        assertTrue(AiOutcomePlan.of(rude, ALL, dating).authoredHearts() < AiOutcomePlan.of(rude, ALL, normal).authoredHearts());
    }

    @Test
    void theParserReadsADateAndABuild() {
        JsonObject date = new JsonObject();
        date.addProperty("type", "action");
        date.addProperty("do", "date");
        date.addProperty("place", "here");
        date.addProperty("when", "tomorrow");
        AiEffect.Action a = (AiEffect.Action) AiReplyParser.parseEffect(date).orElseThrow();
        assertEquals(AiActionKind.DATE, a.kind());
        assertEquals(2, a.amount());
        assertEquals("", a.place(), "here means where they stand");

        JsonObject build = new JsonObject();
        build.addProperty("type", "action");
        build.addProperty("do", "build");
        build.addProperty("build", "hut");
        build.addProperty("helpers", "all");
        AiEffect.Action b = (AiEffect.Action) AiReplyParser.parseEffect(build).orElseThrow();
        assertEquals("hut", b.item());
        assertTrue(b.everyone());
        build.addProperty("build", "castle");
        assertTrue(AiReplyParser.parseEffect(build).isEmpty(), "only templates the game can build");
    }

    // --- childhood, skills -------------------------------------------------------------------------------------

    @Test
    void childhoodReadsAsKindOrCruel() {
        assertEquals("very kind", AiChildhood.tone(8));
        assertEquals("kind", AiChildhood.tone(2));
        assertEquals("", AiChildhood.tone(1));
        assertEquals("unkind", AiChildhood.tone(-3));
        assertEquals("cruel", AiChildhood.tone(-6));
        assertEquals(2, AiChildhood.weigh(AiSentiment.STRONGLY_POSITIVE));
        assertEquals(-1, AiChildhood.weigh(AiSentiment.NEGATIVE));
    }

    @Test
    void practiceMakesBetter() {
        assertEquals(0, AiSkills.level(0));
        assertEquals(1, AiSkills.level(10));
        assertEquals(3, AiSkills.level(100));
        assertEquals(5, AiSkills.level(9_999));
        assertEquals(1.0, AiSkills.speed(0), 1e-9);
        assertEquals(0.6, AiSkills.speed(5), 1e-9);
        assertEquals("a master", AiSkills.words(5));
    }

    // --- life effects in the plan ----------------------------------------------------------------------------------

    private static AiReply reply(AiSentiment sentiment, AiEffect... effects) {
        return new AiReply("line", "", sentiment, 1.0, AiEmotion.HAPPY, Optional.empty(), List.of(effects),
                Optional.empty(), true);
    }

    private static AiTurnFacts facts(Set<String> candidates, Set<String> recipes) {
        return new AiTurnFacts(RelationshipBand.FRIEND, 30, false, false, false, Set.of(), Set.of(), Set.of(),
                Set.of("Bob", "Mara"), Set.of(), 0, false, Set.of(), Set.of(), Set.of(), Set.of(),
                new AiTurnFacts.Life(candidates, recipes, false));
    }

    @Test
    void lifeEffectsAreCheckedAgainstWhatTheVillagerWasShown() {
        AiOutcomePlan plan = AiOutcomePlan.of(reply(AiSentiment.POSITIVE, new AiEffect.Vote("ana"),
                new AiEffect.TeachRecipe("minecraft:cake"), new AiEffect.Teach(AiChore.FISH),
                new AiEffect.SecretTold("Bob", "he is afraid of the dark")), ALL,
                facts(Set.of("Ana", "Luis"), Set.of("minecraft:cake")));
        assertEquals(4, plan.life().size());

        AiOutcomePlan refused = AiOutcomePlan.of(reply(AiSentiment.POSITIVE, new AiEffect.Vote("Pedro"),
                new AiEffect.TeachRecipe("minecraft:beacon"), new AiEffect.SecretTold("Zed", "")), ALL,
                facts(Set.of("Ana", "Luis"), Set.of("minecraft:cake")));
        assertTrue(refused.life().isEmpty(), "not a candidate, not their craft, not a neighbour");

        AiOutcomePlan grumpy = AiOutcomePlan.of(reply(AiSentiment.NEGATIVE, new AiEffect.Teach(AiChore.FISH),
                new AiEffect.Vote("Ana")), ALL, facts(Set.of("Ana", "Luis"), Set.of()));
        assertTrue(grumpy.life().isEmpty(), "nobody learns or changes their vote in a bad exchange");
    }

    @Test
    void theParserReadsLifeEffects() {
        JsonObject vote = new JsonObject();
        vote.addProperty("type", "vote");
        vote.addProperty("for", "Ana");
        assertEquals(Optional.of(new AiEffect.Vote("Ana")), AiReplyParser.parseEffect(vote));
        JsonObject teach = new JsonObject();
        teach.addProperty("type", "teach");
        teach.addProperty("task", "fish");
        assertEquals(Optional.of(new AiEffect.Teach(AiChore.FISH)), AiReplyParser.parseEffect(teach));
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "teach_recipe");
        recipe.addProperty("item", "cake");
        assertEquals(Optional.of(new AiEffect.TeachRecipe("minecraft:cake")), AiReplyParser.parseEffect(recipe));
        JsonObject secret = new JsonObject();
        secret.addProperty("type", "secret_told");
        secret.addProperty("about", "Bob");
        assertTrue(AiReplyParser.parseEffect(secret).isPresent());
    }

    // --- building -----------------------------------------------------------------------------------------------

    @Test
    void templatesHaveTheirShape() {
        List<AiBuild.Piece> hut = AiBuild.layout("hut");
        assertEquals(1, hut.stream().filter(p -> p.role() == AiBuild.Role.DOOR).count());
        assertEquals(25, hut.stream().filter(p -> p.role() == AiBuild.Role.ROOF).count());
        assertEquals(46, hut.stream().filter(p -> p.role() == AiBuild.Role.WALL).count(), "a doorway two high is left");
        assertEquals(24, AiBuild.layout("pen").size());
        assertEquals(1, AiBuild.layout("pen").stream().filter(p -> p.role() == AiBuild.Role.GATE).count());
        for (String template : AiBuild.TEMPLATES) {
            assertFalse(AiBuild.layout(template).isEmpty(), template);
        }
        assertTrue(AiBuild.layout("castle").isEmpty());
    }

    @Test
    void piecesAreLaidOutInFrontOfThePlayer() {
        BlockPos origin = new BlockPos(0, 64, 0);
        AiBuild.Piece ahead = new AiBuild.Piece(0, 0, 3, AiBuild.Role.WALL);
        assertEquals(new BlockPos(0, 64, -3), AiBuild.place(origin, Direction.NORTH, ahead));
        assertEquals(new BlockPos(3, 64, 0), AiBuild.place(origin, Direction.EAST, ahead));
        AiBuild.Piece right = new AiBuild.Piece(2, 1, 0, AiBuild.Role.WALL);
        assertEquals(new BlockPos(2, 65, 0), AiBuild.place(origin, Direction.NORTH, right), "right of someone facing north is east");
    }

    // --- appearance ---------------------------------------------------------------------------------------------

    @Test
    void appearanceIsObservedPlainly() {
        assertEquals("diamond", AiAppearance.armourMaterial(List.of("diamond_helmet", "diamond_chestplate", "iron_boots")));
        assertEquals("", AiAppearance.armourMaterial(List.of("diamond_helmet", "iron_boots")));
        AiAppearance.Look look = new AiAppearance.Look(List.of("netherite_chestplate", "netherite_leggings"), true, false,
                "diamond_sword", true, 5f, 20f, false, true, 4, 80_000, "horse", List.of("wolf"), 30, 35, true, false);
        List<String> lines = AiAppearance.lines(look, "Steve");
        String all = String.join(" | ", lines);
        assertTrue(all.contains("netherite armour"));
        assertTrue(all.contains("shimmers"));
        assertTrue(all.contains("diamond sword"));
        assertTrue(all.contains("badly hurt"));
        assertTrue(all.contains("soaking wet"));
        assertTrue(all.contains("starving"));
        assertTrue(all.contains("exhausted"));
        assertTrue(all.contains("riding a horse"));
        assertTrue(all.contains("wolf"));
        assertTrue(all.contains("never list them"), "the model is told to be natural about it");
        AiAppearance.Look plain = new AiAppearance.Look(List.of(), false, false, "", false, 20f, 20f, false, false, 20, 0,
                "", List.of(), 0, 0, false, false);
        assertTrue(AiAppearance.lines(plain, "Steve").isEmpty(), "nothing worth remarking on");
    }

    // --- dates survive saving ------------------------------------------------------------------------------------

    @Test
    void aDateSurvivesSaving() {
        AiLivesSavedData.Date date = new AiLivesSavedData.Date(UUID.randomUUID(), UUID.randomUUID(), "Ana",
                new BlockPos(1, 2, 3), "inn", 5 * DAY + 11_000);
        date.state = AiLivesSavedData.Date.State.ON;
        date.turns = 3;
        date.warm = 2;
        AiLivesSavedData.Date back = AiLivesSavedData.Date.fromNbt(date.toNbt()).orElseThrow();
        assertEquals(date.villager, back.villager);
        assertEquals(date.spot, back.spot);
        assertEquals(AiLivesSavedData.Date.State.ON, back.state);
        assertEquals(3, back.turns);
        assertEquals(2, back.warm);
    }
}
