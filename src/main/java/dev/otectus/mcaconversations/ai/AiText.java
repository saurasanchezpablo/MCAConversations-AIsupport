package dev.otectus.mcaconversations.ai;

/**
 * Text hygiene for anything the model wrote before it reaches a player, a log or a save file. Model
 * output is untrusted input: it may carry legacy formatting codes, control characters, runaway length
 * or wrapping quotes, and none of that may survive into chat or NBT.
 */
public final class AiText {

    /** Longest line a villager says in one reply, in code points. */
    public static final int MAX_DIALOGUE = 480;
    /** Longest memory a villager keeps, in code points. */
    public static final int MAX_MEMORY = 160;

    private AiText() {
    }

    /**
     * Strips formatting codes ({@code §x}), control characters and invisible format characters (but not
     * ZWNJ/ZWJ), collapses whitespace, removes one pair of wrapping quotes, and truncates to
     * {@code maxCodePoints} (with an ellipsis). Never null.
     */
    public static String clean(String raw, int maxCodePoints) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), maxCodePoints * 2 + 8));
        boolean space = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '§') {
                // Skip the code character too; a dangling section sign at the end is just dropped.
                if (i < raw.length()) {
                    i += Character.charCount(raw.codePointAt(i));
                }
                continue;
            }
            if (Character.isWhitespace(cp) || Character.isISOControl(cp)) {
                space = out.length() > 0;
                continue;
            }
            if (Character.getType(cp) == Character.FORMAT && !joiner(cp)) {
                continue; // invisible: bidi overrides and isolates, zero-width space, tag characters
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.appendCodePoint(cp);
        }
        String text = unquote(out.toString());
        if (text.codePointCount(0, text.length()) > maxCodePoints) {
            int end = text.offsetByCodePoints(0, Math.max(0, maxCodePoints - 1));
            text = text.substring(0, end).stripTrailing() + "…";
        }
        return text;
    }

    /**
     * The zero-width non-joiner and joiner: real text in Persian and Indic scripts and inside emoji
     * sequences, so they are kept. Every other format character is dropped (bidi controls among them).
     */
    private static boolean joiner(int cp) {
        return cp == 0x200C || cp == 0x200D;
    }

    private static String unquote(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '“' && last == '”')) {
                return text.substring(1, text.length() - 1).strip();
            }
        }
        return text;
    }

    /** A lowercase letters-and-digits form, for comparing two memories for "the same thing". */
    public static String fingerprint(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(Character::isLetterOrDigit).map(Character::toLowerCase).forEach(out::appendCodePoint);
        return out.toString();
    }
}
