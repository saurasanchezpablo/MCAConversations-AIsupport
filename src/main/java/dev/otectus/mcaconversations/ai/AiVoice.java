package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import dev.otectus.mcaconversations.network.ConversationsNetwork;
import dev.otectus.mcaconversations.network.VoiceDirectionS2C;
import dev.otectus.mcaconversations.voice.VoiceDirection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Locale;

/**
 * Tells listening clients how a villager's line should sound. The server is where the villager's
 * state lives (grief, a grudge, romance, mood, personality) and where the model's delivery for the
 * line arrives, so the direction is assembled here and sent ahead of the line; each client then
 * voices it with its own speech engine.
 */
final class AiVoice {

    /** MCA delivers villager chat to players around the villager; this is how far a direction is sent. */
    static final double HEARING_RANGE = 32.0;

    private AiVoice() {
    }

    /**
     * Sends the direction for one line.
     *
     * @param text  the exact line, or empty for "the next thing this villager says" (a translated line
     *              whose final text only the client knows)
     * @param facts the turn's facts, or null when the line is not part of a conversation turn
     */
    static void direct(ServerPlayer listener, Entity villager, String text, AiEmotion emotion, AiDelivery delivery,
                       AiTurnFacts facts) {
        if (!(villager.level() instanceof ServerLevel level) || McaHandles.silentVoice(villager)) {
            return;
        }
        boolean romantic = McaCompat.isMarriedToPlayer(villager, listener.getUUID())
                || McaHandles.isEngagedWith(villager, listener.getUUID())
                || McaHandles.isPromisedTo(villager, listener.getUUID());
        VoiceDirection direction = new VoiceDirection(villager.getUUID(), text, clientLanguage(listener),
                emotion.key(), delivery.intent(), delivery.tone(), delivery.pace(), delivery.intensity(), delivery.volume(),
                McaHandles.gender(villager), McaCompat.ageGroup(villager).name().toLowerCase(Locale.ROOT),
                McaCompat.getPersonality(villager).map(AiContextFormat::words).orElse(""),
                McaCompat.getMoodName(villager).map(AiContextFormat::words).orElse(""),
                facts != null && facts.grieving(), romantic, facts != null && facts.grudge());
        VoiceDirectionS2C payload = new VoiceDirectionS2C(direction);
        double range = HEARING_RANGE * HEARING_RANGE;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(villager) <= range) {
                ConversationsNetwork.sendVoice(player, payload);
            }
        }
    }

    /** The player's game language ({@code es_es}, {@code en_us}), or {@code en_us} if unknown. */
    static String clientLanguage(ServerPlayer player) {
        try {
            String language = player.clientInformation().language();
            return language == null || language.isBlank() ? "en_us" : language.toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "en_us";
        }
    }

    /** The language to ask the model to reply in, by name. */
    static String languageName(String code) {
        if (code.startsWith("es")) {
            return "Spanish";
        }
        if (code.startsWith("pt")) {
            return "Portuguese";
        }
        if (code.startsWith("en")) {
            return "English";
        }
        return null;
    }
}
