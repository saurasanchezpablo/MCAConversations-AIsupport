package dev.otectus.mcaconversations.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a {@link VoiceDirection} into an actor's brief for a speech engine. Pure, so what a villager
 * is told to sound like can be tested. Written in English for every language: the engines follow
 * English direction while speaking the line in its own language, and the brief names the accent so a
 * Spanish line is spoken by a native Spanish voice, not read by an English one.
 */
public final class VoiceScript {

    private VoiceScript() {
    }

    /**
     * The language note: speak the line in the language it is written in (the model replies in the
     * player's language, but a player may write in another, and scripted lines may not be translated),
     * with the accent this player's game language implies when the line is in that language.
     */
    public static String languageNote(String code) {
        String c = GameLanguage.normalise(code);
        String language = GameLanguage.plainName(c);
        String note = "Speak the line in the language it is written in, as a native speaker of that language.";
        if (language == null) {
            return note;
        }
        return note + " The listener's game is in " + language + ": if the line is in " + language + ", use "
                + accent(c, language) + ".";
    }

    /** The accent a game language implies, for the languages the game ships in regional forms. */
    static String accent(String code, String language) {
        return switch (GameLanguage.base(code)) {
            case "es" -> code.equals("es_es") || code.equals("esan") ? "a natural accent from Spain"
                    : "a natural Latin American Spanish accent";
            case "en" -> code.equals("en_gb") ? "a natural British accent" : code.equals("en_au") || code.equals("en_nz")
                    ? "a natural Australian accent" : code.equals("en_ca") ? "a natural Canadian accent"
                    : "a natural American accent";
            case "pt" -> code.equals("pt_pt") ? "a natural accent from Portugal" : "a natural Brazilian accent";
            case "fr" -> code.equals("fr_ca") ? "a natural Quebec French accent" : "a natural accent from France";
            case "de" -> code.equals("de_at") ? "a natural Austrian accent" : code.equals("de_ch")
                    ? "a natural Swiss German accent" : "a natural accent from Germany";
            default -> "a natural native " + language + " accent";
        };
    }

    /** The full brief: who is speaking, in what language, feeling what, meaning what, and how. */
    public static String instructions(VoiceDirection d) {
        List<String> parts = new ArrayList<>();
        parts.add("You are voicing " + speaker(d) + " in a small medieval village.");
        parts.add(languageNote(d.language()));
        if (!d.emotion().isEmpty() && !d.emotion().equals("neutral")) {
            parts.add("Emotion: " + d.emotion() + ", " + strength(d.intensity()) + ".");
        }
        parts.add("Delivery: " + d.intent().direction() + ".");
        if (!d.tone().isEmpty()) {
            parts.add("Tone: " + d.tone() + ".");
        }
        if (d.pace() != VoiceDirection.Pace.NORMAL) {
            parts.add("Pace: " + (d.pace() == VoiceDirection.Pace.SLOW ? "slow, with pauses" : "quick, words tumbling out") + ".");
        }
        if (d.volume() != VoiceDirection.Volume.NORMAL) {
            parts.add(d.volume() == VoiceDirection.Volume.WHISPER ? "Whisper it, close and private." : "Raise your voice.");
        }
        if (d.grieving()) {
            parts.add("They are grieving a recent death; it weighs on the voice even when the words are ordinary.");
        }
        if (d.romantic()) {
            parts.add("They are speaking to someone they love; let tenderness show.");
        }
        if (d.cold()) {
            parts.add("They are hurt and holding a grudge against the listener; keep the voice cool and guarded.");
        }
        if (!d.mood().isEmpty()) {
            parts.add("Underlying mood: " + d.mood() + ".");
        }
        parts.add("Sound like a real person talking, not a narrator or an announcer. Do not add or change any words.");
        return String.join(" ", parts);
    }

    /** A one-line style prefix, for engines that take direction inside the prompt (Gemini). */
    public static String stylePrompt(VoiceDirection d) {
        return instructions(d) + "\nSay exactly this line:";
    }

    static String speaker(VoiceDirection d) {
        String age = switch (d.age()) {
            case "child", "toddler", "baby" -> "a young child";
            case "teen" -> "a teenager";
            case "elder" -> "an old";
            default -> "an adult";
        };
        String who = switch (d.gender()) {
            case "female" -> age.equals("a young child") ? "a young girl" : age.equals("a teenager") ? "a teenage girl" : age + " woman";
            case "male" -> age.equals("a young child") ? "a young boy" : age.equals("a teenager") ? "a teenage boy" : age + " man";
            default -> age.equals("an adult") ? "an adult villager" : age;
        };
        return d.personality().isEmpty() ? who : who + " with a " + d.personality() + " personality";
    }

    static String strength(float intensity) {
        return intensity < 0.34f ? "subtle" : intensity < 0.67f ? "clearly felt" : "strong";
    }
}
