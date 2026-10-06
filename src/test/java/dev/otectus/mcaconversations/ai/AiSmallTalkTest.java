package dev.otectus.mcaconversations.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSmallTalkTest {

    private static final String LINES = "{\"lines\": [{\"speaker\": \"Ana\", \"text\": \"Rain again.\"},"
            + "{\"speaker\": \"Luis\", \"text\": \"Good for the wheat.\"}], \"topic\": \"rain\", \"warmth\": 1}";

    @Test
    void thinkingFencesAndStrayBracesAroundTheAnswerAreIgnored() {
        String reply = "<think>maybe {\"lines\": oops} or {x}</think>Here: ```json\n" + LINES + "\n``` {\"note\": 1}";
        AiSmallTalk.Exchange exchange = AiSmallTalk.parse(reply, "Ana", "Luis").orElseThrow();
        assertEquals(2, exchange.lines().size());
        assertEquals("rain", exchange.topic());
        assertEquals(2, AiSmallTalk.parse("{\"mood\": {\"a\": 1}} " + LINES, "Ana", "Luis").orElseThrow().lines().size(),
                "an object without lines before the answer is skipped");
        assertTrue(AiSmallTalk.parse("{ \"lines\": [", "Ana", "Luis").isEmpty());
    }
}
