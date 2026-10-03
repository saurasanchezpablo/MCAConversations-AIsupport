package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.disposition.DispositionAxis;
import dev.otectus.mcaconversations.state.ConversationState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiOutcomePlanTest {

    private static final AiPolicy ALL = new AiPolicy(true, true, 0.6, 12);

    private static AiReply reply(AiSentiment sentiment, double confidence, AiEmotion emotion, AiEffect... effects) {
        return new AiReply("line", "", sentiment, confidence, emotion,
                Optional.of(new AiMemoryNote("note", AiImportance.MEDIUM)), List.of(effects), true);
    }

    @Test
    void heartsComeFromTheBandNeverFromTheModel() {
        assertEquals(3, AiOutcomePlan.of(reply(AiSentiment.STRONGLY_POSITIVE, 1, AiEmotion.HAPPY), ALL).authoredHearts());
        assertEquals(1, AiOutcomePlan.of(reply(AiSentiment.POSITIVE, 1, AiEmotion.HAPPY), ALL).authoredHearts());
        assertEquals(0, AiOutcomePlan.of(reply(AiSentiment.NEUTRAL, 1, AiEmotion.NEUTRAL), ALL).authoredHearts());
        assertEquals(-2, AiOutcomePlan.of(reply(AiSentiment.NEGATIVE, 1, AiEmotion.ANNOYED), ALL).authoredHearts());
        assertEquals(-4, AiOutcomePlan.of(reply(AiSentiment.STRONGLY_NEGATIVE, 1, AiEmotion.ANGRY), ALL).authoredHearts());
    }

    @Test
    void anUncertainJudgementChangesNothingButTheMemory() {
        AiOutcomePlan plan = AiOutcomePlan.of(reply(AiSentiment.STRONGLY_NEGATIVE, 0.4, AiEmotion.ANGRY,
                new AiEffect.DispositionNudge(DispositionAxis.TRUST, -1)), ALL);
        assertEquals(0, plan.authoredHearts());
        assertTrue(plan.state().isEmpty());
        assertTrue(plan.dispositions().isEmpty());
        assertTrue(plan.memory().isPresent());
    }

    @Test
    void aStateIsLeftOnlyWhenTheEmotionAgreesWithTheJudgement() {
        assertEquals(Optional.of(ConversationState.GRATEFUL),
                AiOutcomePlan.of(reply(AiSentiment.POSITIVE, 1, AiEmotion.GRATEFUL), ALL).state());
        assertEquals(Optional.of(ConversationState.ANNOYED),
                AiOutcomePlan.of(reply(AiSentiment.NEGATIVE, 1, AiEmotion.ANGRY), ALL).state());
        assertTrue(AiOutcomePlan.of(reply(AiSentiment.NEGATIVE, 1, AiEmotion.GRATEFUL), ALL).state().isEmpty());
        assertTrue(AiOutcomePlan.of(reply(AiSentiment.POSITIVE, 1, AiEmotion.ANNOYED), ALL).state().isEmpty());
    }

    @Test
    void dispositionNudgesMustPointTheWayTheJudgementDoes() {
        AiOutcomePlan positive = AiOutcomePlan.of(reply(AiSentiment.POSITIVE, 1, AiEmotion.HAPPY,
                new AiEffect.DispositionNudge(DispositionAxis.TRUST, 1),
                new AiEffect.DispositionNudge(DispositionAxis.RESPECT, -1),
                new AiEffect.DispositionNudge(DispositionAxis.TENSION, -1)), ALL);
        assertEquals(Map.of(DispositionAxis.TRUST, 2, DispositionAxis.TENSION, -2), positive.dispositions());

        AiOutcomePlan strongNegative = AiOutcomePlan.of(reply(AiSentiment.STRONGLY_NEGATIVE, 1, AiEmotion.ANGRY,
                new AiEffect.DispositionNudge(DispositionAxis.TENSION, 1),
                new AiEffect.DispositionNudge(DispositionAxis.WARMTH, -1)), ALL);
        assertEquals(Map.of(DispositionAxis.TENSION, 3, DispositionAxis.WARMTH, -3), strongNegative.dispositions());

        assertTrue(AiOutcomePlan.of(reply(AiSentiment.NEUTRAL, 1, AiEmotion.NEUTRAL,
                new AiEffect.DispositionNudge(DispositionAxis.TRUST, 1)), ALL).dispositions().isEmpty());
    }

    @Test
    void switchesTurnTheirEffectsOff() {
        AiReply strong = reply(AiSentiment.STRONGLY_POSITIVE, 1, AiEmotion.GRATEFUL,
                new AiEffect.DispositionNudge(DispositionAxis.WARMTH, 1));
        AiOutcomePlan noHearts = AiOutcomePlan.of(strong, new AiPolicy(false, true, 0.6, 12));
        assertEquals(0, noHearts.authoredHearts());
        assertTrue(noHearts.state().isPresent());

        AiOutcomePlan noGameplay = AiOutcomePlan.of(strong, new AiPolicy(true, false, 0.6, 12));
        assertEquals(3, noGameplay.authoredHearts());
        assertTrue(noGameplay.state().isEmpty());
        assertTrue(noGameplay.dispositions().isEmpty());

        assertTrue(AiOutcomePlan.of(strong, new AiPolicy(true, true, 0.6, 0)).memory().isEmpty());
    }

    @Test
    void anUnstructuredReplyPlansNothing() {
        AiOutcomePlan plan = AiOutcomePlan.of(AiReply.dialogueOnly("Hello."), ALL);
        assertEquals(0, plan.authoredHearts());
        assertTrue(plan.state().isEmpty());
        assertTrue(plan.reaction().isEmpty());
        assertTrue(plan.memory().isEmpty());
        assertEquals("", plan.command());
    }

    @Test
    void decisionIdsAreValidAffectionDecisionIds() {
        for (AiSentiment sentiment : AiSentiment.values()) {
            String id = AiOutcomePlan.of(reply(sentiment, 1, AiEmotion.NEUTRAL), ALL).decision();
            assertTrue(dev.otectus.mcaconversations.conversation.TopicEntry.ID.matcher(id).matches(), id);
        }
    }
}
