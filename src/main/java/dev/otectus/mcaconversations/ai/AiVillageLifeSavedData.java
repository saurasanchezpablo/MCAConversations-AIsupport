package dev.otectus.mcaconversations.ai;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code data/mcaconversations_village_life.dat}: village events (planned, under way and recent), what
 * this mod last saw of each village's residents and couples (to notice newcomers, births and
 * weddings), and peace offers a player carried between two neighbours. Server thread only.
 */
public final class AiVillageLifeSavedData extends SavedData {

    private static final String DATA_NAME = "mcaconversations_village_life";
    /** Ended events are kept this many days for villagers to talk about. */
    static final long KEEP_DAYS = 3;
    /** A peace offer lapses after this many days. */
    static final long PEACE_DAYS = 3;
    static final int MAX_EVENTS = 128;
    static final UUID NOBODY = new UUID(0, 0);

    /** What was last seen of one village, and its politics and troubles. */
    static final class Census {
        boolean initialised;
        long plannedDay = -1;
        final Set<UUID> residents = new HashSet<>();
        final Map<UUID, UUID> partners = new HashMap<>();
        /** Age group last seen, by resident, to notice a child growing up. */
        final Map<UUID, String> ages = new HashMap<>();
        transient boolean changedAges;

        // politics
        UUID leader;
        String leaderName = "";
        String leaderPlatform = "";
        long leaderSince = -1;
        long nextElectionDay = -1;
        final List<UUID> candidates = new ArrayList<>();
        final List<String> candidateNames = new ArrayList<>();
        final List<String> platforms = new ArrayList<>();
        /** Who each villager means to vote for. */
        final Map<UUID, UUID> votes = new HashMap<>();
        /** Which candidate each player campaigned for. */
        final Map<UUID, UUID> backers = new HashMap<>();

        // troubles
        long attackDay = -1;
        int attacks;
        int deaths;
        String attacker = "";
        long meetingDay = -1;
        /** Monsters each player killed in the village. */
        final Map<UUID, Integer> defenders = new HashMap<>();

        boolean electionPending() {
            return candidates.size() == 2;
        }
    }

    /** {@code from} is ready to make peace with {@code to}, as {@code player} carried it. */
    record PeaceOffer(UUID from, UUID to, String fromName, UUID player, long day) {
    }

    private final List<AiVillageEvent> events = new ArrayList<>();
    private final Map<String, Census> census = new HashMap<>();
    private final List<PeaceOffer> peace = new ArrayList<>();
    private int nextId = 1;

    public static AiVillageLifeSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(AiVillageLifeSavedData::new, AiVillageLifeSavedData::load, null), DATA_NAME);
    }

    List<AiVillageEvent> events() {
        return events;
    }

    int nextId() {
        setDirty();
        return nextId++;
    }

    void add(AiVillageEvent event) {
        events.add(event);
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
        setDirty();
    }

    /** The census of one village; call {@link #changed()} after changing it. */
    Census census(String key) {
        return census.computeIfAbsent(key, k -> new Census());
    }

    void changed() {
        setDirty();
    }

    void prune(long now) {
        long today = Math.floorDiv(now, AiVillageEventType.DAY);
        boolean changed = events.removeIf(e -> e.ended && today - Math.floorDiv(e.end, AiVillageEventType.DAY) > KEEP_DAYS);
        changed |= peace.removeIf(p -> today - p.day() > PEACE_DAYS);
        if (changed) {
            setDirty();
        }
    }

    void offerPeace(PeaceOffer offer) {
        peace.removeIf(p -> p.from().equals(offer.from()) && p.to().equals(offer.to()));
        peace.add(offer);
        setDirty();
    }

    /** A live offer of peace from {@code from} to {@code to}. */
    Optional<PeaceOffer> peaceOffer(UUID from, UUID to, long today) {
        return peace.stream().filter(p -> p.from().equals(from) && p.to().equals(to) && today - p.day() <= PEACE_DAYS)
                .findFirst();
    }

    /** Offers of peace made to this villager. */
    List<PeaceOffer> offersTo(UUID to, long today) {
        return peace.stream().filter(p -> p.to().equals(to) && today - p.day() <= PEACE_DAYS).toList();
    }

    void clearPeace(UUID a, UUID b) {
        if (peace.removeIf(p -> (p.from().equals(a) && p.to().equals(b)) || (p.from().equals(b) && p.to().equals(a)))) {
            setDirty();
        }
    }

    /** A villager is gone: events about them stay (a funeral needs its name); offers and census entries go. */
    void removeVillager(UUID villager) {
        boolean changed = peace.removeIf(p -> p.from().equals(villager) || p.to().equals(villager));
        for (Census c : census.values()) {
            changed |= c.partners.remove(villager) != null;
        }
        if (changed) {
            setDirty();
        }
    }

    /** Every village census, by key ({@code dimension|id}). */
    Map<String, Census> censuses() {
        return census;
    }

    private static void readMap(CompoundTag tag, String key, Map<UUID, UUID> into) {
        List<UUID> keys = AiVillageEvent.readUuids(tag, key + "_k");
        List<UUID> values = AiVillageEvent.readUuids(tag, key + "_v");
        for (int i = 0; i < Math.min(keys.size(), values.size()); i++) {
            into.put(keys.get(i), values.get(i));
        }
    }

    private static void writeMap(CompoundTag tag, String key, Map<UUID, UUID> map) {
        tag.put(key + "_k", AiVillageEvent.uuids(map.keySet()));
        tag.put(key + "_v", AiVillageEvent.uuids(map.values()));
    }

    private static AiVillageLifeSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        AiVillageLifeSavedData data = new AiVillageLifeSavedData();
        data.nextId = Math.max(1, tag.getInt("next"));
        ListTag list = tag.getList("events", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            AiVillageEvent.fromNbt(list.getCompound(i)).ifPresent(data.events::add);
        }
        CompoundTag villages = tag.getCompound("census");
        for (String key : villages.getAllKeys()) {
            CompoundTag v = villages.getCompound(key);
            Census c = new Census();
            c.initialised = v.getBoolean("init");
            c.plannedDay = v.contains("planned") ? v.getLong("planned") : -1;
            c.residents.addAll(AiVillageEvent.readUuids(v, "residents"));
            List<UUID> who = AiVillageEvent.readUuids(v, "who");
            List<UUID> with = AiVillageEvent.readUuids(v, "with");
            for (int i = 0; i < Math.min(who.size(), with.size()); i++) {
                c.partners.put(who.get(i), with.get(i));
            }
            CompoundTag ages = v.getCompound("ages");
            for (String id : ages.getAllKeys()) {
                try {
                    c.ages.put(UUID.fromString(id), ages.getString(id));
                } catch (IllegalArgumentException ignored) {
                    // skipped
                }
            }
            if (v.hasUUID("leader")) {
                c.leader = v.getUUID("leader");
            }
            c.leaderName = AiText.clean(v.getString("leader_name"), 64);
            c.leaderPlatform = AiText.clean(v.getString("leader_platform"), 64);
            c.leaderSince = v.contains("leader_since") ? v.getLong("leader_since") : -1;
            c.nextElectionDay = v.contains("next_election") ? v.getLong("next_election") : -1;
            c.candidates.addAll(AiVillageEvent.readUuids(v, "candidates"));
            ListTag names = v.getList("candidate_names", Tag.TAG_STRING);
            ListTag plats = v.getList("platforms", Tag.TAG_STRING);
            for (int i = 0; i < names.size(); i++) {
                c.candidateNames.add(AiText.clean(names.getString(i), 64));
                c.platforms.add(i < plats.size() ? AiText.clean(plats.getString(i), 64) : "");
            }
            if (c.candidates.size() != c.candidateNames.size()) {
                c.candidates.clear();
                c.candidateNames.clear();
                c.platforms.clear();
            }
            readMap(v, "votes", c.votes);
            readMap(v, "backers", c.backers);
            c.attackDay = v.contains("attack_day") ? v.getLong("attack_day") : -1;
            c.attacks = v.getInt("attacks");
            c.deaths = v.getInt("deaths");
            c.attacker = AiText.clean(v.getString("attacker"), 64);
            c.meetingDay = v.contains("meeting_day") ? v.getLong("meeting_day") : -1;
            CompoundTag defenders = v.getCompound("defenders");
            for (String id : defenders.getAllKeys()) {
                try {
                    c.defenders.put(UUID.fromString(id), defenders.getInt(id));
                } catch (IllegalArgumentException ignored) {
                    // skipped
                }
            }
            data.census.put(key, c);
        }
        ListTag offers = tag.getList("peace", Tag.TAG_COMPOUND);
        for (int i = 0; i < offers.size(); i++) {
            CompoundTag p = offers.getCompound(i);
            if (p.hasUUID("from") && p.hasUUID("to") && p.hasUUID("player")) {
                data.peace.add(new PeaceOffer(p.getUUID("from"), p.getUUID("to"), AiText.clean(p.getString("name"), 64),
                        p.getUUID("player"), p.getLong("day")));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt("next", nextId);
        ListTag list = new ListTag();
        events.forEach(e -> list.add(e.toNbt()));
        tag.put("events", list);
        CompoundTag villages = new CompoundTag();
        for (Map.Entry<String, Census> e : census.entrySet()) {
            CompoundTag v = new CompoundTag();
            Census c = e.getValue();
            v.putBoolean("init", c.initialised);
            v.putLong("planned", c.plannedDay);
            v.put("residents", AiVillageEvent.uuids(c.residents));
            List<UUID> who = new ArrayList<>();
            List<UUID> with = new ArrayList<>();
            for (Iterator<Map.Entry<UUID, UUID>> it = c.partners.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, UUID> p = it.next();
                who.add(p.getKey());
                with.add(p.getValue());
            }
            v.put("who", AiVillageEvent.uuids(who));
            v.put("with", AiVillageEvent.uuids(with));
            CompoundTag ages = new CompoundTag();
            c.ages.forEach((id, age) -> ages.putString(id.toString(), age));
            v.put("ages", ages);
            if (c.leader != null) {
                v.putUUID("leader", c.leader);
            }
            v.putString("leader_name", c.leaderName);
            v.putString("leader_platform", c.leaderPlatform);
            v.putLong("leader_since", c.leaderSince);
            v.putLong("next_election", c.nextElectionDay);
            v.put("candidates", AiVillageEvent.uuids(c.candidates));
            ListTag names = new ListTag();
            c.candidateNames.forEach(n -> names.add(net.minecraft.nbt.StringTag.valueOf(n)));
            v.put("candidate_names", names);
            ListTag plats = new ListTag();
            c.platforms.forEach(n -> plats.add(net.minecraft.nbt.StringTag.valueOf(n)));
            v.put("platforms", plats);
            writeMap(v, "votes", c.votes);
            writeMap(v, "backers", c.backers);
            v.putLong("attack_day", c.attackDay);
            v.putInt("attacks", c.attacks);
            v.putInt("deaths", c.deaths);
            v.putString("attacker", c.attacker);
            v.putLong("meeting_day", c.meetingDay);
            CompoundTag defenders = new CompoundTag();
            c.defenders.forEach((id, n) -> defenders.putInt(id.toString(), n));
            v.put("defenders", defenders);
            villages.put(e.getKey(), v);
        }
        tag.put("census", villages);
        ListTag offers = new ListTag();
        for (PeaceOffer p : peace) {
            CompoundTag o = new CompoundTag();
            o.putUUID("from", p.from());
            o.putUUID("to", p.to());
            o.putString("name", p.fromName());
            o.putUUID("player", p.player());
            o.putLong("day", p.day());
            offers.add(o);
        }
        tag.put("peace", offers);
        return tag;
    }
}
