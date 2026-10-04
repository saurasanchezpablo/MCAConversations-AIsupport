package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.conversation.RelationshipBand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a villager says and what then happens must agree. */
class AiConsistencyTest {

    private static final AiPolicy ALL = new AiPolicy(true, true, 0.6, 12);

    private static AiTurnFacts facts(Set<String> actions, boolean grudge) {
        return new AiTurnFacts(RelationshipBand.ACQUAINTANCE, 20, false, false, grudge, Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), 0, false, actions, Set.of("chop", "mine", "fish", "hunt", "harvest"), Set.of());
    }

    private static AiReply reply(String line, AiSentiment sentiment, AiEffect... effects) {
        return new AiReply(line, "", sentiment, 1.0, AiEmotion.HAPPY, Optional.empty(), List.of(effects),
                Optional.empty(), true);
    }

    private static AiConsistency.Decision decide(String message, AiReply reply, AiTurnFacts facts) {
        return AiConsistency.decide(message, reply, AiOutcomePlan.of(reply, ALL, facts), facts);
    }

    @Test
    void commitmentsAreReadFromTheLine() {
        assertEquals(Optional.of(AiChore.CHOP),
                AiCommitment.claims("¡Claro! Voy a talar ahora mismo.").get(AiActionKind.WORK).chore());
        assertTrue(AiCommitment.claims("Vale, te sigo.").containsKey(AiActionKind.FOLLOW));
        assertTrue(AiCommitment.claims("Aquí tienes todo lo que recogí.").containsKey(AiActionKind.GIVE));
        assertTrue(AiCommitment.claims("Sure, I'll go fishing.").containsKey(AiActionKind.WORK));
        assertTrue(AiCommitment.claims("No voy a talar nada.").isEmpty(), "a negated promise is not a promise");
        assertTrue(AiCommitment.accepts("¡Claro que sí!"));
        assertTrue(AiCommitment.accepts("Of course, right away."));
        assertTrue(AiCommitment.refuses("Lo siento, pero no puedo."));
        assertTrue(AiCommitment.refuses("No pienso hacer eso."));
        assertTrue(AiCommitment.refuses("I won't do that."));
        assertFalse(AiCommitment.refuses("¡Claro, voy!"));
    }

    @Test
    void aYesToARequestRunsItEvenWhenTheModelForgotTheAction() {
        AiConsistency.Decision d = decide("ve a talar madera", reply("¡Claro, ahora mismo!", AiSentiment.POSITIVE),
                facts(Set.of("work", "follow"), false));
        assertEquals(1, d.run().size());
        assertEquals(AiActionKind.WORK, d.run().get(0).kind());
        assertEquals(Optional.of(AiChore.CHOP), d.run().get(0).chore());
    }

    @Test
    void aSpokenPromiseRunsWhenThePlayerWasAsking() {
        AiConsistency.Decision d = decide("¿vienes conmigo?", reply("Vale, te sigo.", AiSentiment.POSITIVE),
                facts(Set.of("follow"), false));
        assertEquals(List.of(AiActionKind.FOLLOW), d.run().stream().map(AiEffect.Action::kind).toList());
    }

    @Test
    void smallTalkAboutWorkIsNotAPromise() {
        AiConsistency.Decision d = decide("hola, ¿qué tal?", reply("Bien, mañana voy a talar un poco.", AiSentiment.NEUTRAL),
                facts(Set.of("work"), false));
        assertTrue(d.run().isEmpty());
        assertFalse(d.claimed().containsKey(AiActionKind.WORK));
        assertTrue(AiCommitment.claims("Mañana voy a talar un poco.").isEmpty());
    }

    @Test
    void aRefusalRunsNothing() {
        AiReply no = reply("No, no pienso talar para ti.", AiSentiment.NEGATIVE,
                new AiEffect.Action(AiActionKind.WORK, Optional.of(AiChore.CHOP), 0, ""));
        AiConsistency.Decision d = decide("ve a talar", no, facts(Set.of("work"), false));
        assertTrue(d.refused());
        assertTrue(d.run().isEmpty());
        assertTrue(d.claimed().isEmpty(), "nothing is owed after a no");
    }

    @Test
    void aPromiseThatCannotBeKeptIsAnIssue() {
        // Agreed to chop, but work is not something this villager can be asked to do now.
        AiTurnFacts facts = facts(Set.of("follow"), false);
        AiConsistency.Decision d = decide("ve a talar", reply("¡Claro, voy a talar!", AiSentiment.POSITIVE), facts);
        assertTrue(d.run().isEmpty());
        assertTrue(d.claimed().containsKey(AiActionKind.WORK));
        List<AiActions.Issue> issues = AiConsistency.issues(d, List.of(), facts, "Steve", null);
        assertEquals(1, issues.size());

        // A grudge: the reason given is the grudge.
        AiTurnFacts hurt = facts(Set.of("work"), true);
        AiConsistency.Decision g = decide("ve a talar", reply("Vale, voy a talar.", AiSentiment.POSITIVE), hurt);
        assertTrue(AiConsistency.issues(g, List.of(), hurt, "Steve", null).get(0).why().contains("favours"));
    }

    @Test
    void aFailedActionIsAnIssueAndADoneOneIsNot() {
        AiTurnFacts facts = facts(Set.of("work"), false);
        AiConsistency.Decision d = decide("ve a talar", reply("¡Voy a talar!", AiSentiment.POSITIVE), facts);
        AiActions.Issue noAxe = new AiActions.Issue("you have no axe", null);
        assertEquals(1, AiConsistency.issues(d, List.of(AiActions.Result.failed(AiActionKind.WORK, noAxe)), facts,
                "Steve", null).size());
        assertTrue(AiConsistency.issues(d, List.of(AiActions.Result.ok(AiActionKind.WORK)), facts, "Steve", null).isEmpty());
    }

    @Test
    void theRepairRequestStatesTheTruth() {
        String body = AiRepair.body("m", false, "Ana", "Steve", "Spanish", "¡Voy a talar!",
                List.of("you have no axe to do it"));
        assertTrue(body.contains("you have no axe"));
        assertTrue(body.contains("Spanish"));
        assertTrue(body.contains("\\\"message\\\""));
    }
}
