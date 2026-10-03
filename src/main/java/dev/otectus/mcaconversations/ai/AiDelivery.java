package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.voice.VoiceDirection;
import dev.otectus.mcaconversations.voice.VoiceIntent;

/**
 * How the model says the villager's line should be delivered: what it is for, the tone, pace,
 * intensity and volume. Only ever used for the voice; it changes nothing in the game.
 */
public record AiDelivery(VoiceIntent intent, String tone, VoiceDirection.Pace pace, float intensity,
                         VoiceDirection.Volume volume) {

    /** A plain delivery, derived from the emotion when the model gave none. */
    public static AiDelivery from(AiEmotion emotion, AiSentiment sentiment) {
        VoiceIntent intent = switch (emotion) {
            case GRATEFUL -> VoiceIntent.THANK;
            case ANGRY, ANNOYED -> VoiceIntent.COMPLAIN;
            case AMUSED -> VoiceIntent.TEASE;
            case SMITTEN -> VoiceIntent.FLIRT;
            case SURPRISED -> VoiceIntent.EXCLAIM;
            case SAD, HURT -> VoiceIntent.CONFESS;
            default -> VoiceIntent.STATEMENT;
        };
        float intensity = switch (sentiment) {
            case STRONGLY_POSITIVE, STRONGLY_NEGATIVE -> 0.85f;
            case POSITIVE, NEGATIVE -> 0.55f;
            default -> 0.35f;
        };
        VoiceDirection.Pace pace = emotion == AiEmotion.SAD || emotion == AiEmotion.HURT ? VoiceDirection.Pace.SLOW
                : emotion == AiEmotion.AFRAID || emotion == AiEmotion.SURPRISED ? VoiceDirection.Pace.FAST
                : VoiceDirection.Pace.NORMAL;
        VoiceDirection.Volume volume = emotion == AiEmotion.ANGRY ? VoiceDirection.Volume.RAISED : VoiceDirection.Volume.NORMAL;
        return new AiDelivery(intent, "", pace, intensity, volume);
    }
}
