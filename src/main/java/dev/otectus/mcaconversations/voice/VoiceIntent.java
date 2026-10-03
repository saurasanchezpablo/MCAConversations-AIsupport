package dev.otectus.mcaconversations.voice;

import java.util.Locale;
import java.util.Optional;

/** What a line is <em>for</em>, which shapes how it is said as much as the emotion behind it. */
public enum VoiceIntent {
    STATEMENT("plainly, as a matter of fact"),
    QUESTION("as a genuine question, rising at the end"),
    REASSURE("reassuringly, to put the listener at ease"),
    COMFORT("gently, comforting someone who is hurting"),
    WARN("as a warning, serious and pointed"),
    COMPLAIN("complaining, with a grumble in the voice"),
    TEASE("teasing, playful, half-smiling"),
    FLIRT("flirtatiously, a little shy and warm"),
    CONFESS("confiding something personal, hesitant and quiet"),
    REFUSE("refusing, firm and closed off"),
    APOLOGIZE("apologetic and sincere"),
    THANK("grateful, meaning every word"),
    GREET("as a greeting, welcoming"),
    FAREWELL("saying goodbye"),
    EXCLAIM("as an exclamation, surprised or excited"),
    THREATEN("coldly threatening, low and controlled");

    private final String direction;

    VoiceIntent(String direction) {
        this.direction = direction;
    }

    /** How to say a line with this intent, as an actor's note. */
    public String direction() {
        return direction;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<VoiceIntent> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (VoiceIntent intent : values()) {
            if (intent.name().equals(key)) {
                return Optional.of(intent);
            }
        }
        return Optional.empty();
    }
}
