package dev.otectus.mcaconversations.voice;

import net.minecraft.network.FriendlyByteBuf;

import java.util.Locale;
import java.util.UUID;

/**
 * How one villager line should sound, decided on the server where the villager's state is known and
 * sent ahead of the line itself. Pure data: the client turns it into acting instructions for the
 * speech engine ({@link VoiceScript}). Every string is bounded and sanitised before it is sent.
 *
 * @param villager    who says it
 * @param text        the exact line, or empty to apply to the villager's next line
 * @param language    the language of the line as a Minecraft language code ({@code es_es}, {@code en_us})
 * @param emotion     the emotion shown (an AiEmotion key)
 * @param intent      what the line is for
 * @param tone        a short free-text note on how it is said ("warm, slightly teasing")
 * @param pace        slow, normal or fast
 * @param intensity   0..1, how strongly the emotion shows
 * @param volume      whisper, normal or raised
 * @param gender      male, female or empty
 * @param age         baby, toddler, child, teen, adult or empty
 * @param personality the villager's MCA personality, as words
 * @param mood        the villager's MCA mood, as words
 * @param grieving    in mourning
 * @param romantic    speaking to someone they are courting or married to
 * @param cold        holding a grudge against the listener
 */
public record VoiceDirection(UUID villager, String text, String language, String emotion, VoiceIntent intent,
                             String tone, Pace pace, float intensity, Volume volume, String gender, String age,
                             String personality, String mood, boolean grieving, boolean romantic, boolean cold) {

    /** Longest line a direction may carry. */
    public static final int MAX_TEXT = 600;
    static final int MAX_FIELD = 80;

    public enum Pace { SLOW, NORMAL, FAST }

    public enum Volume { WHISPER, NORMAL, RAISED }

    public VoiceDirection {
        text = bound(text, MAX_TEXT);
        language = bound(language, 16).toLowerCase(Locale.ROOT);
        emotion = bound(emotion, 24).toLowerCase(Locale.ROOT);
        intent = intent == null ? VoiceIntent.STATEMENT : intent;
        tone = bound(tone, MAX_FIELD);
        pace = pace == null ? Pace.NORMAL : pace;
        intensity = Float.isNaN(intensity) ? 0.5f : Math.max(0f, Math.min(1f, intensity));
        volume = volume == null ? Volume.NORMAL : volume;
        gender = bound(gender, 16).toLowerCase(Locale.ROOT);
        age = bound(age, 16).toLowerCase(Locale.ROOT);
        personality = bound(personality, 32);
        mood = bound(mood, 32);
    }

    /** True when this direction applies to whatever the villager says next, not to one exact line. */
    public boolean anyLine() {
        return text.isEmpty();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(villager);
        buf.writeUtf(text, MAX_TEXT * 4);
        buf.writeUtf(language, 64);
        buf.writeUtf(emotion, 96);
        buf.writeUtf(intent.key(), 64);
        buf.writeUtf(tone, MAX_FIELD * 4);
        buf.writeByte(pace.ordinal());
        buf.writeFloat(intensity);
        buf.writeByte(volume.ordinal());
        buf.writeUtf(gender, 64);
        buf.writeUtf(age, 64);
        buf.writeUtf(personality, 128);
        buf.writeUtf(mood, 128);
        buf.writeByte((grieving ? 1 : 0) | (romantic ? 2 : 0) | (cold ? 4 : 0));
    }

    public static VoiceDirection read(FriendlyByteBuf buf) {
        UUID villager = buf.readUUID();
        String text = buf.readUtf(MAX_TEXT * 4);
        String language = buf.readUtf(64);
        String emotion = buf.readUtf(96);
        VoiceIntent intent = VoiceIntent.byKey(buf.readUtf(64)).orElse(VoiceIntent.STATEMENT);
        String tone = buf.readUtf(MAX_FIELD * 4);
        Pace pace = Pace.values()[Math.floorMod(buf.readByte(), Pace.values().length)];
        float intensity = buf.readFloat();
        Volume volume = Volume.values()[Math.floorMod(buf.readByte(), Volume.values().length)];
        String gender = buf.readUtf(64);
        String age = buf.readUtf(64);
        String personality = buf.readUtf(128);
        String mood = buf.readUtf(128);
        int flags = buf.readByte();
        return new VoiceDirection(villager, text, language, emotion, intent, tone, pace, intensity, volume, gender, age,
                personality, mood, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
    }

    /** Strips control and formatting characters and bounds the length, so nothing odd reaches a speech engine. */
    static String bound(String value, int max) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), max));
        value.codePoints().filter(cp -> !Character.isISOControl(cp) && cp != '§')
                .limit(max).forEach(out::appendCodePoint);
        return out.toString().strip();
    }
}
