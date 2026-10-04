package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.conversation.RelationshipBand;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the game knew about this villager and player when the turn started, captured on the server
 * thread so {@link AiOutcomePlan} can stay pure. Every list of "offered" things is exactly what the
 * model was shown; anything the model names that is not in it is refused.
 *
 * @param band             the relationship band
 * @param hearts           MCA hearts
 * @param romanceAllowed   adult, not a blood relative, and not partnered with someone else
 * @param grieving         lost family recently
 * @param grudge           currently refusing this player favours
 * @param offeredQuests    quest ids MCA: Quests says this villager can offer
 * @param offeredTopics    unlockable topic ids
 * @param offeredPlaces    places the villager would give directions to
 * @param neighbours       lower-case neighbour names this villager can hold an opinion about
 * @param bystanders       lower-case names of villagers near enough to chime in
 * @param openPromises     the player's outstanding promises to this villager
 * @param wishActive       the villager already has a wish open with this player
 * @param offeredActions   action keys the villager would do for this player right now
 * @param offeredChores    task keys the villager could be sent to do
 * @param helpers          lower-case names of nearby villagers willing to pitch in
 * @param feuds            lower-case names of neighbours this villager has fallen out with
 * @param life             elections, recipes the villager could teach, a date under way
 */
public record AiTurnFacts(RelationshipBand band, int hearts, boolean romanceAllowed, boolean grieving, boolean grudge,
                          Set<String> offeredQuests, Set<String> offeredTopics, Set<String> offeredPlaces,
                          Set<String> neighbours, Set<String> bystanders, int openPromises, boolean wishActive,
                          Set<String> offeredActions, Set<String> offeredChores, Set<String> helpers,
                          Set<String> feuds, Life life) {

    /**
     * The rest of the villager's life this turn.
     *
     * @param candidates lower-case names of the candidates in the village election
     * @param recipes    item ids of recipes the villager could teach the player
     * @param onDate     the player and the villager are on a date right now
     */
    public record Life(Set<String> candidates, Set<String> recipes, boolean onDate) {
        public static final Life NONE = new Life(Set.of(), Set.of(), false);

        public Life {
            candidates = lower(candidates);
            recipes = recipes == null ? Set.of() : Set.copyOf(recipes);
        }
    }

    /** MCA's bouquet threshold: below it, no courtship can start. */
    public static final int ROMANCE_MIN_HEARTS = 10;
    /** Most outstanding promises one player can owe one villager. */
    public static final int MAX_OPEN_PROMISES = 3;

    public AiTurnFacts {
        band = band == null ? RelationshipBand.STRANGER : band;
        offeredQuests = offeredQuests == null ? Set.of() : Set.copyOf(offeredQuests);
        offeredTopics = offeredTopics == null ? Set.of() : Set.copyOf(offeredTopics);
        offeredPlaces = offeredPlaces == null ? Set.of() : Set.copyOf(offeredPlaces);
        neighbours = lower(neighbours);
        bystanders = lower(bystanders);
        offeredActions = offeredActions == null ? Set.of() : Set.copyOf(offeredActions);
        offeredChores = offeredChores == null ? Set.of() : Set.copyOf(offeredChores);
        helpers = lower(helpers);
        feuds = lower(feuds);
        life = life == null ? Life.NONE : life;
    }

    /** Without the rest of life. */
    public AiTurnFacts(RelationshipBand band, int hearts, boolean romanceAllowed, boolean grieving, boolean grudge,
                       Set<String> offeredQuests, Set<String> offeredTopics, Set<String> offeredPlaces,
                       Set<String> neighbours, Set<String> bystanders, int openPromises, boolean wishActive,
                       Set<String> offeredActions, Set<String> offeredChores, Set<String> helpers, Set<String> feuds) {
        this(band, hearts, romanceAllowed, grieving, grudge, offeredQuests, offeredTopics, offeredPlaces, neighbours,
                bystanders, openPromises, wishActive, offeredActions, offeredChores, helpers, feuds, Life.NONE);
    }

    /** Without feuds. */
    public AiTurnFacts(RelationshipBand band, int hearts, boolean romanceAllowed, boolean grieving, boolean grudge,
                       Set<String> offeredQuests, Set<String> offeredTopics, Set<String> offeredPlaces,
                       Set<String> neighbours, Set<String> bystanders, int openPromises, boolean wishActive,
                       Set<String> offeredActions, Set<String> offeredChores, Set<String> helpers) {
        this(band, hearts, romanceAllowed, grieving, grudge, offeredQuests, offeredTopics, offeredPlaces, neighbours,
                bystanders, openPromises, wishActive, offeredActions, offeredChores, helpers, Set.of(), Life.NONE);
    }

    /** Without helpers. */
    public AiTurnFacts(RelationshipBand band, int hearts, boolean romanceAllowed, boolean grieving, boolean grudge,
                       Set<String> offeredQuests, Set<String> offeredTopics, Set<String> offeredPlaces,
                       Set<String> neighbours, Set<String> bystanders, int openPromises, boolean wishActive,
                       Set<String> offeredActions, Set<String> offeredChores) {
        this(band, hearts, romanceAllowed, grieving, grudge, offeredQuests, offeredTopics, offeredPlaces, neighbours,
                bystanders, openPromises, wishActive, offeredActions, offeredChores, Set.of());
    }

    /** Without spoken actions. */
    public AiTurnFacts(RelationshipBand band, int hearts, boolean romanceAllowed, boolean grieving, boolean grudge,
                       Set<String> offeredQuests, Set<String> offeredTopics, Set<String> offeredPlaces,
                       Set<String> neighbours, Set<String> bystanders, int openPromises, boolean wishActive) {
        this(band, hearts, romanceAllowed, grieving, grudge, offeredQuests, offeredTopics, offeredPlaces, neighbours,
                bystanders, openPromises, wishActive, Set.of(), Set.of(), Set.of());
    }

    /** A turn about which nothing is known: nothing beyond hearts, states and dispositions is allowed. */
    public static AiTurnFacts none() {
        return new AiTurnFacts(RelationshipBand.STRANGER, 0, false, false, false, Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), 0, false);
    }

    public boolean atLeast(RelationshipBand floor) {
        return band.isAtLeast(floor);
    }

    private static Set<String> lower(Set<String> names) {
        if (names == null) {
            return Set.of();
        }
        return Set.copyOf(names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList());
    }

    static boolean contains(Set<String> lowerNames, String name) {
        return name != null && lowerNames.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Convenience for tests and callers holding name to UUID maps. */
    static Set<String> names(Map<String, ?> byName) {
        return byName == null ? Set.of() : byName.keySet();
    }
}
