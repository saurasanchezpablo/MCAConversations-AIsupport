package dev.otectus.mcaconversations.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSocialParserTest {

    private static List<AiEffect> effects(String json) {
        return AiReplyParser.parse("{\"message\": \"ok\", \"effects\": [" + json + "]}").orElseThrow().effects();
    }

    @Test
    void promisesAreNormalisedAndBounded() {
        assertEquals(List.of(new AiEffect.Promise("minecraft:wheat", AiReplyParser.MAX_PROMISE_COUNT, 7, "bring wheat")),
                effects("{\"type\":\"promise\",\"item\":\"Wheat\",\"count\":500,\"days\":30,\"summary\":\"bring wheat\"}"));
        assertEquals(List.of(new AiEffect.Promise("#minecraft:logs", 1, 1, "")),
                effects("{\"type\":\"promise\",\"item\":\"#minecraft:logs\",\"count\":0,\"days\":-3}"));
        AiEffect.Promise visit = (AiEffect.Promise) effects("{\"type\":\"promise\",\"days\":2,\"summary\":\"come back\"}").get(0);
        assertTrue(visit.isVisit());
        assertEquals(0, visit.count());
        assertTrue(effects("{\"type\":\"promise\",\"item\":\"a lovely cake!!\"}").isEmpty(),
                "a named item that is not an item id makes no promise");
    }

    @Test
    void wishesQuestsTopicsPlacesAndOpinionsParse() {
        assertEquals(List.of(new AiEffect.Wish("minecraft:blue_orchid", 10, "reminds me of home")),
                effects("{\"type\":\"wish\",\"item\":\"blue_orchid\",\"days\":99,\"summary\":\"reminds me of home\"}"));
        assertEquals(List.of(new AiEffect.OfferQuest("mcaquests:help_baker")),
                effects("{\"type\":\"offer_quest\",\"quest\":\"MCAQuests:help_baker\"}"));
        assertTrue(effects("{\"type\":\"offer_quest\",\"quest\":\"help the baker\"}").isEmpty());
        assertEquals(List.of(new AiEffect.UnlockTopic("confided")), effects("{\"type\":\"unlock_topic\",\"topic\":\"confided\"}"));
        assertEquals(List.of(new AiEffect.Directions("minecraft:mineshaft")),
                effects("{\"type\":\"directions\",\"place\":\"minecraft:mineshaft\"}"));
        assertEquals(List.of(new AiEffect.Opinion("Bob", "trust", -1, "he lied to me")),
                effects("{\"type\":\"opinion\",\"about\":\"Bob\",\"axis\":\"trust\",\"direction\":\"down\",\"cause\":\"he lied to me\"}"));
        assertTrue(effects("{\"type\":\"opinion\",\"about\":\"Bob\",\"axis\":\"lust\",\"direction\":\"up\"}").isEmpty());
        assertEquals(3, effects("{\"type\":\"discount\"},{\"type\":\"forgive\"},{\"type\":\"grudge\"}").size());
    }

    @Test
    void interjectionsAndSecretsParse() {
        AiReply reply = AiReplyParser.parse("{\"message\": \"Shh.\", \"memory\": {\"text\": \"They told me their real name.\", "
                + "\"importance\": \"high\", \"secret\": true}, \"interjection\": {\"speaker\": \"Bob\", "
                + "\"message\": \"\\u00a7cWhat are you two whispering about?\"}}").orElseThrow();
        assertTrue(reply.memory().orElseThrow().secret());
        assertEquals("Bob", reply.interjection().orElseThrow().speaker());
        assertEquals("What are you two whispering about?", reply.interjection().orElseThrow().message());
        assertTrue(AiReplyParser.parse("{\"message\": \"x\", \"interjection\": {\"speaker\": \"\", \"message\": \"y\"}}")
                .orElseThrow().interjection().isEmpty());
    }
}
