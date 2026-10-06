package dev.otectus.mcaconversations.ai;

import java.util.Locale;
import java.util.Optional;

/**
 * The model's own reading of one exchange: what the player's last message asked the villager to do,
 * what the villager answered, and what the villager's line says they were given. The model reads the
 * meaning, in whatever language and words were used ("¿me cortas leña?", "Holz hacken, bitte",
 * "I could really use some wood", "tiens, c'est pour toi"), so nothing depends on fixed phrases.
 *
 * <p>It comes from the reply itself ({@code "request"} and {@code "received"}), or, when a model leaves
 * them out, from a short second request that reads the exchange ({@link AiJudge}). The game still
 * decides: a request only runs if it was offered to this villager now ({@link AiConsistency}), and a
 * claim of having been given something is checked against what really changed hands.
 *
 * @param asked    the kind of thing the player asked for, even when its details were unusable
 * @param request  the request with its details (task, amount, item, place, helpers), when they parse
 * @param answer   the villager's answer to it, as their line gives it
 * @param received what the villager's line says the player just gave or lent them: an item id, or
 *                 {@link #SOMETHING} when the line does not say what; empty when it says nothing of the sort
 */
public record AiUnderstanding(Optional<AiActionKind> asked, Optional<AiEffect.Action> request, Answer answer,
                              Optional<String> received) {

    /** The villager's line thanks the player for a gift without saying what. */
    public static final String SOMETHING = "something";

    /** The villager's answer to the player's request. */
    public enum Answer {
        /** Agrees, and will do it now. */
        YES,
        /** Refuses, or cannot. */
        NO,
        /** Agrees for some other time: nothing runs now. */
        LATER,
        /** There was nothing to answer, or the line does not answer it. */
        NONE;

        public static Answer byKey(String raw) {
            if (raw == null) {
                return NONE;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "yes", "y", "true", "agree", "agreed", "accept", "accepted", "ok", "okay", "sure" -> YES;
                case "no", "n", "false", "refuse", "refused", "decline", "declined", "cannot", "can't", "cant" -> NO;
                case "later", "not_now", "not now", "tomorrow", "another_time" -> LATER;
                default -> NONE;
            };
        }
    }

    /** Nothing asked, nothing answered, nothing received. */
    public static final AiUnderstanding NOTHING = new AiUnderstanding(Optional.empty(), Optional.empty(), Answer.NONE,
            Optional.empty());

    public AiUnderstanding {
        asked = asked == null ? Optional.empty() : asked;
        request = request == null ? Optional.empty() : request;
        answer = answer == null ? Answer.NONE : answer;
        received = received == null ? Optional.empty() : received;
        if (asked.isEmpty() && request.isPresent()) {
            asked = Optional.of(request.get().kind());
        }
    }

    /** The villager turned the request down. */
    public boolean refused() {
        return answer == Answer.NO;
    }

    /** The villager agreed to do what was asked, now. */
    public boolean agreed() {
        return answer == Answer.YES && asked.isPresent();
    }
}
