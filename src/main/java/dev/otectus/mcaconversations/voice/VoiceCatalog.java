package dev.otectus.mcaconversations.voice;

import java.util.List;
import java.util.UUID;

/**
 * Which voice a villager has with each engine. Stable: the same villager always gets the same voice
 * (picked from their UUID within the set that fits their gender and age), so a villager is
 * recognisable by voice, and two villagers rarely share one. Pure, so the choice is testable.
 */
public final class VoiceCatalog {

    static final List<String> OPENAI_FEMALE = List.of("coral", "nova", "shimmer", "sage");
    static final List<String> OPENAI_MALE = List.of("ash", "ballad", "echo", "onyx", "verse");
    static final List<String> OPENAI_NEUTRAL = List.of("alloy", "fable");
    static final List<String> OPENAI_CHILD = List.of("nova", "shimmer", "fable");

    static final List<String> GEMINI_FEMALE = List.of("Kore", "Aoede", "Leda", "Zephyr", "Autonoe", "Callirrhoe",
            "Despina", "Erinome", "Laomedeia", "Pulcherrima", "Vindemiatrix", "Achernar", "Gacrux", "Sulafat");
    static final List<String> GEMINI_MALE = List.of("Puck", "Charon", "Fenrir", "Orus", "Enceladus", "Iapetus",
            "Umbriel", "Algieba", "Algenib", "Rasalgethi", "Achird", "Zubenelgenubi", "Sadachbia", "Sadaltager",
            "Alnilam", "Schedar");
    static final List<String> GEMINI_CHILD = List.of("Leda", "Zephyr", "Puck", "Fenrir");

    private VoiceCatalog() {
    }

    public static String voiceFor(VoiceProvider provider, UUID villager, String gender, String age) {
        boolean child = age.equals("child") || age.equals("toddler") || age.equals("baby");
        List<String> pool = switch (provider) {
            case GEMINI -> child ? GEMINI_CHILD : gender.equals("female") ? GEMINI_FEMALE
                    : gender.equals("male") ? GEMINI_MALE : GEMINI_FEMALE;
            default -> child ? OPENAI_CHILD : gender.equals("female") ? OPENAI_FEMALE
                    : gender.equals("male") ? OPENAI_MALE : OPENAI_NEUTRAL;
        };
        long mixed = villager.getMostSignificantBits() ^ Long.rotateLeft(villager.getLeastSignificantBits(), 17);
        return pool.get((int) Math.floorMod(mixed, (long) pool.size()));
    }
}
