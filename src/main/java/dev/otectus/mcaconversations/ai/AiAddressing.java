package dev.otectus.mcaconversations.ai;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a chat line names a villager. Pure: accents and case are ignored, whole words only.
 *
 * <p>Three kinds of name are told apart:
 * <ul>
 *   <li>Names in scripts written without spaces between words (Han, kana, Hangul, Thai and the like)
 *       are found anywhere in the line, since "小明你好", "太郎さん" or "철수야" have no word boundary to
 *       find; a two-character given name counts there.</li>
 *   <li>A full name of several words is distinctive enough to count wherever it appears.</li>
 *   <li>A single word (a one-word name, or the first name alone) in a cased script may also be an
 *       ordinary word: Will, May, Rose, Sol, Paz. It counts only when it reads as a name or a call:
 *       capitalised mid-sentence ("ask Will"), followed by vocative punctuation ("will, come here",
 *       "thanks rose!") or ending the line ("hey sofia"). "I will go" or "Will you help?" do not;
 *       a player who types "sofia help me" still reaches her by looking at her or already talking.</li>
 * </ul>
 */
public final class AiAddressing {

    private static final Pattern MARKS = Pattern.compile("\\p{M}");
    /** A run of letters and digits as written, its accents kept with it. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");
    /** Separators inside a name: spaces, and the katakana and Latin middle dots. */
    private static final Pattern NAME_PARTS = Pattern.compile("[\\s\\u30FB\\u00B7]+");
    /** Right after a name, these make it a call: "Will, ...", "Rose!", "May?". */
    private static final String VOCATIVE = ",!?:;…、，！？：；";
    /** Before a word, these end a sentence, so a capital there says nothing. */
    private static final String SENTENCE_END = ".!?¡¿…。！？";
    /** Only punctuation, symbols and space: the line ends here. */
    private static final Pattern TAIL = Pattern.compile("[\\p{P}\\p{S}\\s]*");

    private AiAddressing() {
    }

    static String normalise(String text) {
        return MARKS.matcher(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
    }

    /** True when the message names the villager by full name or by first name (see the class notes). */
    public static boolean mentions(String message, String villagerName) {
        String raw = villagerName == null ? "" : villagerName.strip();
        String name = normalise(raw).strip();
        if (name.isEmpty() || message == null) {
            return false;
        }
        String[] parts = NAME_PARTS.split(name);
        String first = parts[0];
        if (unspaced(raw)) {
            String text = normalise(message).replaceAll("\\s+", "");
            return text.contains(name.replaceAll("\\s+", "").replace("・", "").replace("·", ""))
                    || (first.codePointCount(0, first.length()) >= 2 && text.contains(first));
        }
        String text = normalise(message);
        if (parts.length > 1 && whole(text, name)) {
            return true;
        }
        if (parts.length > 1 && first.codePointCount(0, first.length()) < 3) {
            return false;
        }
        return cased(raw) ? called(message, first) : whole(text, first);
    }

    private static boolean whole(String text, String word) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])").matcher(text).find();
    }

    /** One word of a cased-script name, used as a name or a call rather than as an ordinary word. */
    private static boolean called(String message, String word) {
        if (!WORD.matcher(word).matches()) {
            // A name with a hyphen or an apostrophe is unusual enough to count anywhere.
            return whole(normalise(message), word);
        }
        Matcher m = WORD.matcher(message);
        while (m.find()) {
            if (!normalise(m.group()).equals(word)) {
                continue;
            }
            String after = message.substring(m.end());
            String rest = after.stripLeading();
            if (TAIL.matcher(after).matches() || (!rest.isEmpty() && VOCATIVE.indexOf(rest.charAt(0)) >= 0)) {
                return true;
            }
            String before = message.substring(0, m.start()).stripTrailing();
            int head = m.group().codePointAt(0);
            boolean capital = Character.isUpperCase(head) || Character.isTitleCase(head);
            boolean sentenceStart = before.isEmpty() || SENTENCE_END.indexOf(before.charAt(before.length() - 1)) >= 0;
            if (capital && !sentenceStart) {
                return true;
            }
        }
        return false;
    }

    /** True when the name is written in a script with letter case (Latin, Cyrillic, Greek, ...). */
    private static boolean cased(String name) {
        return name.codePoints().filter(Character::isLetter)
                .anyMatch(cp -> Character.toUpperCase(cp) != Character.toLowerCase(cp));
    }

    /** True when the name is in a script that does not put spaces between words. */
    private static boolean unspaced(String name) {
        return name.codePoints().anyMatch(cp -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
                    || script == Character.UnicodeScript.THAI || script == Character.UnicodeScript.LAO
                    || script == Character.UnicodeScript.KHMER || script == Character.UnicodeScript.MYANMAR;
        });
    }
}
