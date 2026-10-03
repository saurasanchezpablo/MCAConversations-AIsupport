package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.conversation.ReactionSemantic;
import dev.otectus.mcaconversations.conversation.RelationshipBand;
import dev.otectus.mcaconversations.disposition.DispositionAxis;
import dev.otectus.mcaconversations.state.ConversationState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The social half of AiOutcomePlan: every request is checked against what the game showed the model. */
class AiSocialPlanTest {

    private static final AiPolicy ALL = new AiPolicy(true, true, 0.6, 12);

    private static AiTurnFacts facts(RelationshipBand band, int hearts, boolean romance, boolean grieving, boolean grudge) {
        return new AiTurnFacts(band, hearts, romance, grieving, grudge, Set.of("mcaquests:help_baker"),
                Set.of("confided"), Set.of("blacksmith", "minecraft:mineshaft"), Set.of("Bob", "Mara"),
                Set.of("Bob"), 0, false);
    }

    private static AiReply reply(AiSentiment sentiment, AiEmotion emotion, AiEffect... effects) {
        return new AiReply("line", "", sentiment, 1.0, emotion,
                Optional.of(new AiMemoryNote("something happened", AiImportance.HIGH)), List.of(effects),
                Optional.empty(), true);
    }

    @Test
    void romanceIsGatedByEligibilityAndCourtshipThreshold() {
        AiReply flirt = reply(AiSentiment.POSITIVE, AiEmotion.SMITTEN, new AiEffect.DispositionNudge(DispositionAxis.ATTRACTION, 1));
        AiOutcomePlan allowed = AiOutcomePlan.of(flirt, ALL, facts(RelationshipBand.FRIEND, 40, true, false, false));
        assertEquals(Optional.of(ConversationState.SMITTEN), allowed.state());
        assertEquals(2, allowed.dispositions().get(DispositionAxis.ATTRACTION));

        AiOutcomePlan notEligible = AiOutcomePlan.of(flirt, ALL, facts(RelationshipBand.FRIEND, 40, false, false, false));
        assertTrue(notEligible.state().isEmpty(), "no smitten state for a child, a relative or someone else's partner");
        assertFalse(notEligible.dispositions().containsKey(DispositionAxis.ATTRACTION));

        AiOutcomePlan tooSoon = AiOutcomePlan.of(flirt, ALL, facts(RelationshipBand.STRANGER, 5, true, false, false));
        assertFalse(tooSoon.dispositions().containsKey(DispositionAxis.ATTRACTION), "below MCA's bouquet threshold");
    }

    @Test
    void crueltyToTheBereavedCutsDeeperAndComfortBuildsTrust() {
        assertEquals(-6, AiOutcomePlan.of(reply(AiSentiment.STRONGLY_NEGATIVE, AiEmotion.HURT), ALL,
                facts(RelationshipBand.FRIEND, 40, false, true, false)).authoredHearts());
        assertEquals(-3, AiOutcomePlan.of(reply(AiSentiment.NEGATIVE, AiEmotion.HURT), ALL,
                facts(RelationshipBand.FRIEND, 40, false, true, false)).authoredHearts());
        AiOutcomePlan comfort = AiOutcomePlan.of(reply(AiSentiment.POSITIVE, AiEmotion.GRATEFUL), ALL,
                facts(RelationshipBand.FRIEND, 40, false, true, false));
        assertEquals(2, comfort.dispositions().get(DispositionAxis.TRUST));
    }

    @Test
    void aGrudgeIsHeldOnStrongHurtAndLiftedOnlyByASincereApology() {
        AiOutcomePlan hurt = AiOutcomePlan.of(reply(AiSentiment.STRONGLY_NEGATIVE, AiEmotion.ANGRY), ALL,
                facts(RelationshipBand.FRIEND, 40, false, false, false));
        assertTrue(hurt.grudge());

        AiOutcomePlan sorry = AiOutcomePlan.of(reply(AiSentiment.POSITIVE, AiEmotion.HAPPY, new AiEffect.Forgive()), ALL,
                facts(RelationshipBand.FRIEND, 40, false, false, true));
        assertTrue(sorry.forgive());
        assertFalse(sorry.grudge());
        assertEquals(Optional.of(ReactionSemantic.REPAIR), sorry.reaction());
        assertEquals(-2, sorry.dispositions().get(DispositionAxis.TENSION));

        AiOutcomePlan insincere = AiOutcomePlan.of(reply(AiSentiment.NEGATIVE, AiEmotion.ANNOYED, new AiEffect.Forgive()),
                ALL, facts(RelationshipBand.FRIEND, 40, false, false, true));
        assertFalse(insincere.forgive(), "forgiveness needs the exchange itself to have gone well");

        AiOutcomePlan nothingToForgive = AiOutcomePlan.of(reply(AiSentiment.POSITIVE, AiEmotion.HAPPY, new AiEffect.Forgive()),
                ALL, facts(RelationshipBand.FRIEND, 40, false, false, false));
        assertFalse(nothingToForgive.forgive());
    }

    @Test
    void aVillagerHoldingAGrudgeDoesNoFavours() {
        AiReply asks = new AiReply("Fine.", "follow-player", AiSentiment.NEUTRAL, 1.0, AiEmotion.NEUTRAL,
                Optional.empty(), List.of(new AiEffect.Directions("blacksmith"), new AiEffect.OfferQuest("mcaquests:help_baker"),
                new AiEffect.Discount()), Optional.empty(), true);
        AiOutcomePlan plan = AiOutcomePlan.of(asks, ALL, facts(RelationshipBand.FRIEND, 40, false, false, true));
        assertEquals("", plan.command());
        assertTrue(plan.directions().isEmpty());
        assertTrue(plan.questOffer().isEmpty());
        assertEquals(0, plan.tradeMood());

        AiReply leave = new AiReply("Go.", "move-freely", AiSentiment.NEUTRAL, 1.0, AiEmotion.NEUTRAL,
                Optional.empty(), List.of(), Optional.empty(), true);
        assertEquals("move-freely", AiOutcomePlan.of(leave, ALL, facts(RelationshipBand.FRIEND, 40, false, false, true)).command());
    }

    @Test
    void onlyOfferedQuestsTopicsPlacesAndNamesAreAccepted() {
        AiTurnFacts known = facts(RelationshipBand.FRIEND, 40, false, false, false);
        AiOutcomePlan ok = AiOutcomePlan.of(reply(AiSentiment.POSITIVE, AiEmotion.HAPPY,
                new AiEffect.OfferQuest("mcaquests:help_baker"), new AiEffect.UnlockTopic("confided"),
                new AiEffect.Directions("blacksmith"), new AiEffect.Opinion("bob", "trust", -1, "he lied")), ALL, known);
        assertEquals(Optional.of("mcaquests:help_baker"), ok.questOffer());
        assertEquals(Optional.of("confided"), ok.unlockTopic());
        assertEquals(Optional.of("blacksmith"), ok.directions());
        assertTrue(ok.opinion().isPresent(), "names match case-insensitively");

        AiOutcomePlan invented = AiOutcomePlan.of(reply(AiSentiment.POSITIVE, AiEmotion.HAPPY,
                new AiEffect.OfferQuest("mcaquests:slay_dragon"), new AiEffect.UnlockTopic("secret_vault"),
                new AiEffect.Directions("minecraft:stronghold"), new AiEffect.Opinion("Zed", "trust", -1, "")), ALL, known);
        assertTrue(invented.questOffer().isEmpty());
        assertTrue(invented.unlockTopic().isEmpty());
        assertTrue(invented.directions().isEmpty());
        assertTrue(invented.opinion().isEmpty());
    }

    @Test
    void interjectionsOnlyFromSomeoneActuallyNearby() {
        AiReply withBob = new AiReply("Hi.", "", AiSentiment.NEUTRAL, 1.0, AiEmotion.NEUTRAL, Optional.empty(), List.of(),
                Optional.of(new AiInterjection("BOB", "Don't listen to her!")), true);
        assertTrue(AiOutcomePlan.of(withBob, ALL, facts(RelationshipBand.FRIEND, 40, false, false, false)).interjection().isPresent());
        AiReply withMara = new AiReply("Hi.", "", AiSentiment.NEUTRAL, 1.0, AiEmotion.NEUTRAL, Optional.empty(), List.of(),
                Optional.of(new AiInterjection("Mara", "Hello!")), true);
        assertTrue(AiOutcomePlan.of(withMara, ALL, facts(RelationshipBand.FRIEND, 40, false, false, false)).interjection().isEmpty(),
                "Mara is a neighbour, not within earshot");
    }

    @Test
    void secretsNeverBecomeVillageTalk() {
        AiReply told = new AiReply("Thank you.", "", AiSentiment.STRONGLY_POSITIVE, 1.0, AiEmotion.GRATEFUL,
                Optional.of(new AiMemoryNote("They saved my brother.", AiImportance.HIGH, false)), List.of(), Optional.empty(), true);
        assertEquals(Optional.of(AiOutcomePlan.GossipTone.KIND), AiOutcomePlan.of(told, ALL).gossip());
        AiReply secret = new AiReply("Thank you.", "", AiSentiment.STRONGLY_POSITIVE, 1.0, AiEmotion.GRATEFUL,
                Optional.of(new AiMemoryNote("They know about my debts.", AiImportance.HIGH, true)), List.of(), Optional.empty(), true);
        assertTrue(AiOutcomePlan.of(secret, ALL).gossip().isEmpty());
        AiReply mild = reply(AiSentiment.POSITIVE, AiEmotion.HAPPY);
        assertTrue(AiOutcomePlan.of(mild, ALL).gossip().isEmpty(), "only strongly felt exchanges are told around");
    }

    @Test
    void pricesFollowTheMood() {
        AiTurnFacts friend = facts(RelationshipBand.FRIEND, 70, false, false, false);
        assertEquals(AiOutcomePlan.TRADE_BONUS, AiOutcomePlan.of(reply(AiSentiment.POSITIVE, AiEmotion.HAPPY,
                new AiEffect.Discount()), ALL, friend).tradeMood());
        assertEquals(0, AiOutcomePlan.of(reply(AiSentiment.POSITIVE, AiEmotion.HAPPY, new AiEffect.Discount()), ALL,
                facts(RelationshipBand.ACQUAINTANCE, 30, false, false, false)).tradeMood(), "discounts are for friends");
        assertEquals(-AiOutcomePlan.TRADE_PENALTY, AiOutcomePlan.of(reply(AiSentiment.NEGATIVE, AiEmotion.ANNOYED), ALL, friend).tradeMood());
        assertEquals(-AiOutcomePlan.STRONG_TRADE_PENALTY,
                AiOutcomePlan.of(reply(AiSentiment.STRONGLY_NEGATIVE, AiEmotion.ANGRY), ALL, friend).tradeMood());
    }

    @Test
    void promisesAndWishesRespectTheirLimits() {
        AiEffect.Promise wheat = new AiEffect.Promise("minecraft:wheat", 10, 2, "bring wheat");
        AiTurnFacts full = new AiTurnFacts(RelationshipBand.FRIEND, 40, false, false, false, Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), AiTurnFacts.MAX_OPEN_PROMISES, true);
        AiOutcomePlan plan = AiOutcomePlan.of(reply(AiSentiment.POSITIVE, AiEmotion.HAPPY, wheat,
                new AiEffect.Wish("minecraft:poppy", 5, "")), ALL, full);
        assertTrue(plan.promise().isEmpty(), "no more than three open promises");
        assertTrue(plan.wish().isEmpty(), "one wish at a time");
        assertTrue(AiOutcomePlan.of(reply(AiSentiment.POSITIVE, AiEmotion.HAPPY, wheat), ALL,
                facts(RelationshipBand.STRANGER, 0, false, false, false)).promise().isPresent());
    }

    @Test
    void anUnsureModelChangesNothingSocial() {
        AiReply unsure = new AiReply("Hm.", "follow-player", AiSentiment.STRONGLY_NEGATIVE, 0.3, AiEmotion.ANGRY,
                Optional.of(new AiMemoryNote("x", AiImportance.HIGH)),
                List.of(new AiEffect.Grudge(), new AiEffect.Promise("", 0, 1, "")), Optional.empty(), true);
        AiOutcomePlan plan = AiOutcomePlan.of(unsure, ALL, facts(RelationshipBand.FRIEND, 40, false, false, false));
        assertFalse(plan.grudge());
        assertTrue(plan.promise().isEmpty());
        assertTrue(plan.gossip().isEmpty());
        assertEquals(0, plan.tradeMood());
    }
}
