package dev.otectus.mcaconversations.ai;

import java.util.Locale;
import java.util.Optional;

/**
 * A task a villager can be sent to do. Four are MCA's own chores (MCA does the work and keeps what it
 * gathers in the villager's inventory); mining is this mod's, because MCA lists a "prospecting" chore
 * but ships no task for it, so this mod drives that one itself.
 *
 * @param mcaCommand the command MCA's interaction handler assigns the chore with
 * @param tool       the tool the villager needs in its inventory, in words
 * @param yield      what it gathers, in words
 */
public enum AiChore {
    CHOP("chopping", "an axe", "logs"),
    HARVEST("harvesting", "a hoe", "crops"),
    HUNT("hunting", "a sword", "meat"),
    FISH("fishing", "a fishing rod", "fish"),
    MINE("prospecting", "a pickaxe", "stone and ore");

    private final String mcaCommand;
    private final String tool;
    private final String yield;

    AiChore(String mcaCommand, String tool, String yield) {
        this.mcaCommand = mcaCommand;
        this.tool = tool;
        this.yield = yield;
    }

    public String mcaCommand() {
        return mcaCommand;
    }

    public String tool() {
        return tool;
    }

    public String yield() {
        return yield;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** By this enum's key or by MCA's chore name ({@code chop}, {@code chopping}, {@code mining}...). */
    public static Optional<AiChore> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (AiChore chore : values()) {
            if (chore.key().equals(key) || chore.mcaCommand.equals(key)) {
                return Optional.of(chore);
            }
        }
        return switch (key) {
            case "mining", "prospect", "stone", "dig" -> Optional.of(MINE);
            case "wood", "lumber", "logging" -> Optional.of(CHOP);
            case "farm", "farming" -> Optional.of(HARVEST);
            default -> Optional.empty();
        };
    }

    /** The MCA chore name ({@code CHOP}, {@code PROSPECT}...) this task shows up as on the villager. */
    public String mcaChoreName() {
        return this == MINE ? "PROSPECT" : name();
    }
}
