package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.voice.VoiceDirection;
import dev.otectus.mcaconversations.voice.VoiceIntent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiDeliveryTest {

    @Test
    void theModelsDeliveryIsParsedAndBounded() {
        AiReply reply = AiReplyParser.parse("{\"message\": \"Ven aquí...\", \"delivery\": {\"intent\": \"confess\", "
                + "\"tone\": \"quiet, nervous\", \"pace\": \"slow\", \"intensity\": 7, \"volume\": \"whisper\"}}").orElseThrow();
        AiDelivery d = reply.delivery().orElseThrow();
        assertEquals(VoiceIntent.CONFESS, d.intent());
        assertEquals("quiet, nervous", d.tone());
        assertEquals(VoiceDirection.Pace.SLOW, d.pace());
        assertEquals(VoiceDirection.Volume.WHISPER, d.volume());
        assertTrue(d.intensity() <= 1f);
    }

    @Test
    void anUnknownDeliveryFallsBackFieldByField() {
        AiDelivery d = AiReplyParser.parse("{\"message\": \"x\", \"delivery\": {\"intent\": \"interpretive dance\", "
                + "\"pace\": \"ludicrous\"}}").orElseThrow().delivery().orElseThrow();
        assertEquals(VoiceIntent.STATEMENT, d.intent());
        assertEquals(VoiceDirection.Pace.NORMAL, d.pace());
    }

    @Test
    void withoutADeliveryTheEmotionDecidesHowItSounds() {
        AiReply angry = AiReplyParser.parse("{\"message\": \"¡Fuera!\", \"emotion\": \"angry\", "
                + "\"assessment\": {\"impact\": \"strongly_negative\", \"confidence\": 0.9}}").orElseThrow();
        AiDelivery d = angry.deliveryOrDefault();
        assertEquals(VoiceIntent.COMPLAIN, d.intent());
        assertEquals(VoiceDirection.Volume.RAISED, d.volume());
        assertTrue(d.intensity() > 0.8f);
        assertEquals(VoiceIntent.THANK, AiDelivery.from(AiEmotion.GRATEFUL, AiSentiment.POSITIVE).intent());
        assertEquals(VoiceDirection.Pace.SLOW, AiDelivery.from(AiEmotion.SAD, AiSentiment.NEGATIVE).pace());
    }
}
