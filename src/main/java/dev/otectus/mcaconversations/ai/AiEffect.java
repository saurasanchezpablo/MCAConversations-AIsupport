package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.disposition.DispositionAxis;

import java.util.Optional;

/**
 * One gameplay effect the model <em>requested</em>. A request only: every effect type is parsed by
 * {@link AiReplyParser} into a typed record with bounded parameters, {@link AiOutcomePlan} decides
 * whether it is legal for this villager, this player and this moment, and {@link AiOutcomeApplier}
 * applies it through the system that owns that kind of change. A type the parser does not know is
 * dropped, so the model can never reach an effect this mod did not write.
 *
 * <p>Adding a type: a record here, a parse branch in {@link AiReplyParser#parseEffect}, a line in the
 * schema {@link AiPromptBuilder} describes, a rule in {@link AiOutcomePlan} and an apply step.
 */
public sealed interface AiEffect permits AiEffect.DispositionNudge, AiEffect.Promise, AiEffect.Wish,
        AiEffect.OfferQuest, AiEffect.UnlockTopic, AiEffect.Opinion, AiEffect.Directions, AiEffect.Discount,
        AiEffect.Forgive, AiEffect.Grudge, AiEffect.Action {

    /** The stable key the model uses for this effect type. */
    String type();

    /**
     * Moves one axis of the villager's disposition vector toward the player by one step. The model
     * picks the axis and the direction; the step size, the daily cap and the anti-farming guard are
     * the disposition system's own.
     *
     * @param direction {@code +1} or {@code -1}
     */
    record DispositionNudge(DispositionAxis axis, int direction) implements AiEffect {
        public static final String TYPE = "disposition";

        public DispositionNudge {
            direction = Integer.signum(direction);
        }

        @Override
        public String type() {
            return TYPE;
        }
    }

    /**
     * The player promised something: to bring items ({@code item} set, {@code count} of them) or to
     * come back ({@code item} empty). The villager will remember, check, and react when it is kept or
     * broken.
     *
     * @param item    an item id or {@code #tag}, or empty for a promise to return
     * @param days    days until it is due
     * @param summary what was promised, in the villager's words
     */
    record Promise(String item, int count, int days, String summary) implements AiEffect {
        public static final String TYPE = "promise";

        public boolean isVisit() {
            return item == null || item.isEmpty();
        }

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** The villager let slip that they would love an item. A gift of it within {@code days} means a lot. */
    record Wish(String item, int days, String summary) implements AiEffect {
        public static final String TYPE = "wish";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** The villager brings up one of the quests MCA: Quests says they can offer right now. */
    record OfferQuest(String questId) implements AiEffect {
        public static final String TYPE = "offer_quest";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** The villager now trusts the player enough for a deeper conversation topic. */
    record UnlockTopic(String topic) implements AiEffect {
        public static final String TYPE = "unlock_topic";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** What the player said changed how the villager sees a neighbour. */
    record Opinion(String about, String axis, int direction, String cause) implements AiEffect {
        public static final String TYPE = "opinion";

        public Opinion {
            direction = Integer.signum(direction);
        }

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** The villager tells the player the way to a place they know of. */
    record Directions(String place) implements AiEffect {
        public static final String TYPE = "directions";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** The villager wants to give the player better prices. */
    record Discount() implements AiEffect {
        public static final String TYPE = "discount";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** The villager accepts the player's apology and lets go of a grudge. */
    record Forgive() implements AiEffect {
        public static final String TYPE = "forgive";

        @Override
        public String type() {
            return TYPE;
        }
    }

    /**
     * Something the player asked the villager to do, which the villager agrees to.
     *
     * @param chore  for {@link AiActionKind#WORK}
     * @param amount for WORK, how much to gather before coming back (0 = until told to stop);
     *               for GIVE, how many items
     * @param item   for GIVE and FETCH, which item (id)
     * @param place  for GUIDE and WAIT_AT, a place token the villager was shown
     */
    record Action(AiActionKind kind, Optional<AiChore> chore, int amount, String item, String place) implements AiEffect {
        public static final String TYPE = "action";

        public Action {
            chore = chore == null ? Optional.empty() : chore;
            amount = Math.max(0, Math.min(64, amount));
            item = item == null ? "" : item;
            place = place == null ? "" : place;
        }

        public Action(AiActionKind kind, Optional<AiChore> chore, int amount, String item) {
            this(kind, chore, amount, item, "");
        }

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** The villager is hurt enough to refuse the player favours for a while. */
    record Grudge() implements AiEffect {
        public static final String TYPE = "grudge";

        @Override
        public String type() {
            return TYPE;
        }
    }
}
