package dev.otectus.mcaconversations.ai;

import java.util.Locale;
import java.util.Optional;

/** Something a player can ask a villager to do, by word. A closed set: nothing else can be requested. */
public enum AiActionKind {
    /** Open the villager's trade window. */
    TRADE,
    /** Accept the item in the player's main hand as a gift (MCA's own gift rules decide how it lands). */
    GIFT,
    /** Open the villager's inventory, so the player can hand over tools or take things back. */
    INVENTORY,
    FOLLOW,
    STAY,
    /** Stop following or staying: move freely again. */
    MOVE,
    GO_HOME,
    /** Put on, or take off, any armour the villager carries. */
    ARMOR,
    /** Start a task (see {@link AiChore}), optionally until an amount is gathered. */
    WORK,
    STOP_WORK,
    /** Hand the player items from the villager's own inventory. */
    GIVE;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<AiActionKind> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (AiActionKind kind : values()) {
            if (kind.name().equals(key)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
