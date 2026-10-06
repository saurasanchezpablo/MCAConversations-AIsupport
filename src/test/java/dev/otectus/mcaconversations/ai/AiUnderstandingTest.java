package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.conversation.RelationshipBand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the player asked and what the villager answered come from the model's reading, so a request works
 * in any language and any wording: none of these lines would match a phrase list.
 */
class AiUnderstandingTest {

    private static final AiPolicy ALL = new AiPolicy(true, true, 0.6, 12);

    private static AiTurnFacts facts(Set<String> actions, boolean grudge) {
        return new AiTurnFacts(RelationshipBand.ACQUAINTANCE, 20, false, false, grudge, Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), 0, false, actions, Set.of("chop", "mine", "fish", "hunt", "harvest"), Set.of("bob"));
    }

    private static AiReply parse(String json) {
        return AiReplyParser.parse(json).orElseThrow();
    }

    private static AiConsistency.Decision decide(String message, AiReply reply, AiTurnFacts facts) {
        return AiConsistency.decide(message, reply, AiOutcomePlan.of(reply, ALL, facts), facts);
    }

    @Test
    void theRequestAndAnswerAreParsed() {
        AiReply reply = parse("{\"message\": \"Na klar, ich hacke dir zehn Stämme.\", "
                + "\"request\": {\"do\": \"work\", \"task\": \"chop\", \"amount\": 10, \"answer\": \"yes\"}, \"received\": null}");
        AiUnderstanding u = reply.understanding().orElseThrow();
        assertEquals(Optional.of(AiActionKind.WORK), u.asked());
        assertEquals(Optional.of(AiChore.CHOP), u.request().orElseThrow().chore());
        assertEquals(10, u.request().orElseThrow().amount());
        assertTrue(u.agreed());
        assertTrue(u.received().isEmpty());

        // Nothing asked is still a reading: the model looked and found no request.
        AiUnderstanding none = parse("{\"message\": \"Bonjour !\", \"request\": null, \"received\": null}")
                .understanding().orElseThrow();
        assertTrue(none.asked().isEmpty());
        // A reply without either field has no reading at all.
        assertTrue(parse("{\"message\": \"Hello.\"}").understanding().isEmpty());
        // Received: an item, or "something".
        assertEquals(Optional.of("minecraft:iron_axe"), parse("{\"message\": \"x\", \"received\": \"iron_axe\"}")
                .understanding().orElseThrow().received());
        assertEquals(Optional.of(AiUnderstanding.SOMETHING), parse("{\"message\": \"x\", \"received\": \"something\"}")
                .understanding().orElseThrow().received());
    }

    @Test
    void aYesInAnyLanguageRunsWhatWasAskedEvenWithoutTheAction() {
        AiReply reply = parse("{\"message\": \"Na klar, mache ich!\", \"assessment\": {\"impact\": \"neutral\", "
                + "\"confidence\": 0.9}, \"request\": {\"do\": \"work\", \"task\": \"chop\", \"amount\": 10, \"answer\": \"yes\"}}");
        AiConsistency.Decision d = decide("Könntest du mir bitte etwas Holz hacken?", reply, facts(Set.of("work"), false));
        assertEquals(1, d.run().size());
        assertEquals(AiActionKind.WORK, d.run().get(0).kind());
        assertEquals(Optional.of(AiChore.CHOP), d.run().get(0).chore());
        assertEquals(10, d.run().get(0).amount());
        assertTrue(d.claimed().containsKey(AiActionKind.WORK));
    }

    @Test
    void aHintIsARequestWhenTheModelReadsItAsOne() {
        // "I could really use some company on the way" in Japanese: no imperative, no keyword.
        AiReply reply = parse("{\"message\": \"いいよ、一緒に行こう。\", \"request\": {\"do\": \"follow\", \"answer\": \"yes\"}}");
        AiConsistency.Decision d = decide("道中、誰かと一緒だと心強いんだけどな…", reply, facts(Set.of("follow"), false));
        assertEquals(List.of(AiActionKind.FOLLOW), d.run().stream().map(AiEffect.Action::kind).toList());
    }

    @Test
    void aRefusalInAnyLanguageRunsNothingEvenWithTheActionAttached() {
        AiReply reply = parse("{\"message\": \"Non, je n'ai pas le temps.\", \"effects\": [{\"type\": \"action\", "
                + "\"do\": \"work\", \"task\": \"mine\"}], \"request\": {\"do\": \"work\", \"task\": \"mine\", \"answer\": \"no\"}}");
        AiConsistency.Decision d = decide("Tu peux miner un peu de pierre ?", reply, facts(Set.of("work"), false));
        assertTrue(d.refused());
        assertTrue(d.run().isEmpty());
        assertTrue(d.claimed().isEmpty());
    }

    @Test
    void laterRunsNothingNowAndIsNotHeldAgainstTheVillager() {
        AiReply reply = parse("{\"message\": \"Domani, promesso.\", \"request\": {\"do\": \"work\", \"task\": \"fish\", "
                + "\"answer\": \"later\"}}");
        AiConsistency.Decision d = decide("Vai a pescare?", reply, facts(Set.of("work"), false));
        assertTrue(d.run().isEmpty());
        assertTrue(d.claimed().isEmpty());
        assertFalse(d.refused());
    }

    @Test
    void handingSomethingOverOpensTheGiftWindowUnlessTurnedDown() {
        AiReply reply = parse("{\"message\": \"Oh! Für mich?\", \"request\": {\"do\": \"gift\"}}");
        AiConsistency.Decision d = decide("Hier, das ist für dich.", reply, facts(Set.of("gift"), false));
        assertEquals(List.of(AiActionKind.GIFT), d.run().stream().map(AiEffect.Action::kind).toList());

        AiReply no = parse("{\"message\": \"Nein danke.\", \"request\": {\"do\": \"gift\", \"answer\": \"no\"}}");
        assertTrue(decide("Hier, das ist für dich.", no, facts(Set.of("gift"), false)).run().isEmpty());
    }

    @Test
    void askingForSomethingBackIsGiveWithThatItem() {
        AiReply reply = parse("{\"message\": \"Claro, toma tu hacha.\", \"request\": {\"do\": \"give\", "
                + "\"item\": \"minecraft:iron_axe\", \"amount\": 1, \"answer\": \"yes\"}}");
        AiConsistency.Decision d = decide("¿Me devuelves mi hacha?", reply, facts(Set.of("give"), false));
        assertEquals(1, d.run().size());
        assertEquals("minecraft:iron_axe", d.run().get(0).item());
    }

    @Test
    void anAgreedRequestThatWasNotOfferedIsHeldToTheTruth() {
        AiReply reply = parse("{\"message\": \"Sure, I'll bring it right over.\", \"request\": {\"do\": \"fetch\", "
                + "\"item\": \"minecraft:bread\", \"amount\": 2, \"answer\": \"yes\"}}");
        AiTurnFacts facts = facts(Set.of("follow"), false);
        AiConsistency.Decision d = decide("Grab me some bread from the chest?", reply, facts);
        assertTrue(d.run().isEmpty());
        assertTrue(d.claimed().containsKey(AiActionKind.FETCH));
        assertEquals(1, AiConsistency.issues(d, List.of(), facts, "Steve", null).size());
    }

    @Test
    void helpersAreOnlyThoseShownAsWilling() {
        AiReply reply = parse("{\"message\": \"Ok!\", \"request\": {\"do\": \"follow\", \"helpers\": [\"Bob\", \"Zed\"], "
                + "\"answer\": \"yes\"}}");
        AiConsistency.Decision d = decide("Bob y tú, venid conmigo", reply, facts(Set.of("follow"), false));
        assertEquals(List.of("Bob"), d.run().get(0).helpers());
    }

    @Test
    void aGrudgeStillForbidsFavoursWhateverTheReading() {
        AiReply reply = parse("{\"message\": \"Fine.\", \"request\": {\"do\": \"work\", \"task\": \"chop\", \"answer\": \"yes\"}}");
        AiConsistency.Decision d = decide("chop for me", reply, facts(Set.of("work"), true));
        assertTrue(d.run().isEmpty());
    }

    @Test
    void claimsOfReceiptAreCheckedFromTheReading() {
        AiReply axe = parse("{\"message\": \"Danke für die Axt!\", \"received\": \"minecraft:iron_axe\"}");
        // Not given and not held: corrected, in any language.
        assertTrue(AiConsistency.receipt(axe, i -> false, i -> Optional.of(AiChore.CHOP), c -> false, false, "Steve")
                .isPresent());
        // Held, or handed over just now: fine.
        assertTrue(AiConsistency.receipt(axe, i -> true, i -> Optional.of(AiChore.CHOP), c -> true, false, "Steve")
                .isEmpty());
        assertTrue(AiConsistency.receipt(axe, i -> false, i -> Optional.empty(), c -> false, true, "Steve").isEmpty());
        // Says nothing was received: nothing to check, even if the words look like thanks.
        AiReply none = parse("{\"message\": \"¡Gracias por el hacha!\", \"received\": null}");
        assertTrue(AiConsistency.receipt(none, i -> false, i -> Optional.empty(), c -> false, false, "Steve").isEmpty());
    }

    @Test
    void theJudgeReadingParsesAndAFormatlessAnswerIsAFailure() {
        assertTrue(AiReplyParser.parseUnderstanding("{\"request\": {\"do\": \"stay\", \"answer\": \"yes\"}, \"received\": null}")
                .orElseThrow().agreed());
        assertTrue(AiReplyParser.parseUnderstanding("{\"request\": null, \"received\": null}").orElseThrow().asked().isEmpty());
        assertTrue(AiReplyParser.parseUnderstanding("{\"message\": \"Hello\"}").isEmpty());
        assertTrue(AiReplyParser.parseUnderstanding("I think they asked to follow.").isEmpty());
    }

    @Test
    void theJudgeIsShownTheExchangeAndTheMenu() {
        String body = AiJudge.body("m", true, new AiJudge.Exchange("Ana", "Steve",
                List.of(new AiSessions.Line(false, "¿Te ayudo con algo?")), "sí, tala unos árboles", "¡Vale!",
                List.of("{\"type\": \"action\", \"do\": \"work\"}")));
        assertTrue(body.contains("sí, tala unos árboles"));
        assertTrue(body.contains("¿Te ayudo con algo?"));
        assertTrue(body.contains("\\\"do\\\": \\\"work\\\""));
        assertTrue(body.contains("any language"));
        assertTrue(body.contains("json_object"));
    }

    @Test
    void thePromptAsksForTheReadingOnlyWhenTheVillagerCouldAct() {
        AiPromptInput with = input(List.of("{\"type\": \"action\", \"do\": \"work\"} ..."));
        AiPromptInput without = input(List.of());
        assertTrue(AiPromptBuilder.system(with).contains("\"request\""));
        assertTrue(AiPromptBuilder.system(with).contains("whatever language"));
        assertFalse(AiPromptBuilder.system(without).contains("\"request\""));
    }

    @Test
    void aConversationTheVillagerOpenedStillStartsWithTheUser() {
        AiPromptInput in = new AiPromptInput("m", "", false, false, false, false, "German", false, 1L,
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "Steve", "Ana", "", "", "", List.of(), List.of(),
                0, List.of(), List.of(new AiSessions.Line(false, "Hallo!"), new AiSessions.Line(false, "Na?")),
                "hallo", List.of(), false);
        com.google.gson.JsonArray messages = com.google.gson.JsonParser.parseString(AiPromptBuilder.body(in))
                .getAsJsonObject().getAsJsonArray("messages");
        List<String> roles = new java.util.ArrayList<>();
        messages.forEach(m -> roles.add(m.getAsJsonObject().get("role").getAsString()));
        assertEquals(List.of("system", "user", "assistant", "user"), roles);
        assertTrue(messages.get(2).getAsJsonObject().get("content").getAsString().contains("Hallo!\nNa?"));
    }

    @Test
    void linesSaidWhileWaitingKeepTheirOrderAndTheNewestSurvives() {
        assertEquals("first second", AiConversations.joinLines("first", "second"));
        String newest = "x".repeat(200);
        String joined = AiConversations.joinLines("y".repeat(200), newest);
        assertTrue(joined.endsWith(newest));
        assertTrue(joined.length() <= 256);
    }

    @Test
    void aReasoningModelsThinkingIsNeverTakenForTheReply() {
        AiReply reply = AiReplyParser.parse("<think>Draft: {\"message\": \"wrong\"} hmm {</think>"
                + "{\"message\": \"Hola\", \"request\": {\"do\": \"stay\", \"answer\": \"yes\"}}").orElseThrow();
        assertEquals("Hola", reply.dialogue());
        assertTrue(reply.understanding().orElseThrow().agreed());
        // Prose with braces after the reply does not break it.
        assertEquals("Ok", AiReplyParser.parse("{\"message\": \"Ok\"}\n(Note: {player} asked nicely)")
                .orElseThrow().dialogue());
        // Thinking with no reply is not something a villager says.
        assertTrue(AiReplyParser.parse("<think>The user wants me to").isEmpty());
    }

    private static AiPromptInput input(List<String> offers) {
        return new AiPromptInput("m", "", false, false, false, false, "German", false, 1L, java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "Steve", "Ana", "", "", "", List.of(), List.of(), 0, List.of(), List.of(),
                "hallo", offers, false);
    }
}
