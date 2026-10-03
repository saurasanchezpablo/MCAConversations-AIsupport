package dev.otectus.mcaconversations.ai;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Whether a chat line names a villager. Pure: accents and case are ignored, whole words only. */
public final class AiAddressing {

    private static final Pattern MARKS = Pattern.compile("\\p{M}");

    private AiAddressing() {
    }

    static String normalise(String text) {
        return MARKS.matcher(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
    }

    /** True when the message names the villager by full name or by first name, as a whole word. */
    public static boolean mentions(String message, String villagerName) {
        String text = normalise(message);
        String name = normalise(villagerName).strip();
        if (name.isEmpty()) {
            return false;
        }
        if (whole(text, name)) {
            return true;
        }
        String first = name.split("\\s+")[0];
        return first.length() >= 3 && whole(text, first);
    }

    private static boolean whole(String text, String word) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])").matcher(text).find();
    }
}
