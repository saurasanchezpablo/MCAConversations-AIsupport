package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.disposition.DispositionAxis;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure wording for the structured context: numbers become bands a model can use, ids become words.
 * Raw numbers are deliberately not shown for dispositions: "trust: low" steers a reply; "trust: -17"
 * invites the model to reason about a scale it does not know.
 */
public final class AiContextFormat {

    /** Most entries of any list or set shown in one line. */
    static final int MAX_LIST = 6;
    /** An id as this mod and MCA write them: lower case, no spaces, maybe namespaced. */
    private static final Pattern ID = Pattern.compile("[a-z0-9_.:/#-]+");

    private AiContextFormat() {
    }

    /** {@code "town_center"} → {@code "town center"}; {@code "mca:odd"} → {@code "odd"}. */
    public static String words(String id) {
        if (id == null) {
            return "";
        }
        String value = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return value.replace('_', ' ').replace('.', ' ').trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Up to {@link #MAX_LIST} entries, comma-separated. Ids ({@code "lost_ring"}, {@code "mca:odd"})
     * become words; anything else, such as the names of the villagers nearby, is shown as written, so
     * "Alice" stays "Alice" and "María José" keeps its case.
     */
    public static String list(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream().filter(v -> v != null && !v.toString().isBlank()).limit(MAX_LIST)
                .map(v -> entry(v.toString())).collect(Collectors.joining(", "));
    }

    private static String entry(String value) {
        return ID.matcher(value).matches() ? words(value) : value.strip();
    }

    /** A disposition axis value as a band. TENSION runs 0..100; the others -100..100 around a baseline of 0. */
    public static String dispositionBand(DispositionAxis axis, int value) {
        if (axis == DispositionAxis.TENSION) {
            return value < 10 ? "none" : value < 40 ? "some" : value < 70 ? "high" : "very high";
        }
        if (value <= -40) {
            return "very low";
        }
        if (value < -10) {
            return "low";
        }
        if (value <= 10) {
            return "neutral";
        }
        return value < 40 ? "high" : "very high";
    }

    /** "today", "yesterday", "N days ago" or "never". */
    public static String daysAgo(long days) {
        return days < 0 ? "never" : AiPromptBuilder.ago(days);
    }
}
