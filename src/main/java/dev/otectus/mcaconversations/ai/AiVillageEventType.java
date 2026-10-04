package dev.otectus.mcaconversations.ai;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The kinds of village life events. Gatherings bring villagers together at a place for a while and
 * reward the players who come; a quarrel is two neighbours falling out, which a player can mend.
 *
 * <p>Times are in ticks from the start of the in-game day (0 = 6 am, 6000 = noon, 12000 = 6 pm).
 *
 * @param gathering      villagers gather at a place
 * @param startTime      when it starts, ticks into the day
 * @param duration       how long it lasts, in ticks
 * @param places         building types to hold it at, in order of preference
 * @param guestHearts    hearts a player who comes gains with each villager there
 * @param honoureeHearts hearts with the people it is for (the bereaved, the couple, the parents, the newcomer)
 */
public enum AiVillageEventType {
    FESTIVAL(true, 10_500, 2_000, List.of("inn", "music_store", "tavern", "town_center"), 1, 1),
    HARVEST_FEAST(true, 10_000, 2_200, List.of("town_center", "inn", "farm", "tavern"), 1, 1),
    MARKET(true, 1_500, 4_500, List.of("town_center", "market", "inn", "storage"), 0, 0),
    FUNERAL(true, 3_000, 1_600, List.of("graveyard", "cemetery", "chapel", "church", "town_center"), 1, 2),
    WEDDING(true, 6_000, 2_500, List.of("chapel", "church", "town_center", "inn"), 1, 2),
    BIRTH(true, 5_000, 1_500, List.of("town_center", "inn", "infirmary"), 1, 2),
    WELCOME(true, 2_500, 1_500, List.of("town_center", "inn"), 1, 2),
    /** The village talks over an attack, the morning after. */
    MEETING(true, 3_000, 1_500, List.of("town_center", "inn", "guard_tower", "armory"), 1, 1),
    /** The village chooses its leader. */
    ELECTION(true, 6_000, 1_800, List.of("town_center", "inn", "library"), 0, 0),
    QUARREL(false, 6_000, 400, List.of(), 0, 0);

    public static final int DAY = 24_000;
    /** Gatherings must be over by then, before villagers head to bed. */
    public static final int LATEST_END = 13_000;

    private final boolean gathering;
    private final int startTime;
    private final int duration;
    private final List<String> places;
    private final int guestHearts;
    private final int honoureeHearts;

    AiVillageEventType(boolean gathering, int startTime, int duration, List<String> places, int guestHearts,
                       int honoureeHearts) {
        this.gathering = gathering;
        this.startTime = startTime;
        this.duration = duration;
        this.places = places;
        this.guestHearts = guestHearts;
        this.honoureeHearts = honoureeHearts;
    }

    public boolean gathering() {
        return gathering;
    }

    public int startTime() {
        return startTime;
    }

    public int duration() {
        return duration;
    }

    public List<String> places() {
        return places;
    }

    public int guestHearts() {
        return guestHearts;
    }

    public int honoureeHearts() {
        return honoureeHearts;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Events a village plans on its own, by weight: festival 30, market 30, harvest feast 15, quarrel 25. */
    public static AiVillageEventType pickPlanned(double roll) {
        double r = Math.max(0, Math.min(0.999_999, roll)) * 100;
        if (r < 30) {
            return FESTIVAL;
        }
        if (r < 60) {
            return MARKET;
        }
        return r < 75 ? HARVEST_FEAST : QUARREL;
    }

    /**
     * When an event of this type planned at {@code now} (absolute day time) starts: today at its hour,
     * or a little after now if that hour has just passed; empty when it could no longer finish before
     * evening, so it is not held today. Pure.
     */
    public Optional<Long> startToday(long now, long lead) {
        long dayStart = Math.floorDiv(now, DAY) * DAY;
        long start = Math.max(dayStart + startTime, now + lead);
        if (gathering && start + duration > dayStart + LATEST_END) {
            return Optional.empty();
        }
        return Optional.of(start);
    }

    /** As {@link #startToday}, falling back to tomorrow at its hour. Pure. */
    public long startSoon(long now, long lead) {
        return startToday(now, lead).orElse((Math.floorDiv(now, DAY) + 1) * DAY + startTime);
    }

    public static Optional<AiVillageEventType> byKey(String key) {
        for (AiVillageEventType type : values()) {
            if (type.key().equals(key)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
