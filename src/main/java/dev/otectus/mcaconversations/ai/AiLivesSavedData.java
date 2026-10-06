package dev.otectus.mcaconversations.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code data/mcaconversations_lives.dat}: the personal threads of villagers' lives that outlast a
 * conversation. It holds four things:
 * <ul>
 *   <li>dates agreed with players;</li>
 *   <li>how a player treated a villager as a child;</li>
 *   <li>what each villager has learned to do well;</li>
 *   <li>secrets a player passed on, waiting for the one who confided them to find out.</li>
 * </ul>
 * Server thread only.
 */
public final class AiLivesSavedData extends SavedData {

    private static final String DATA_NAME = "mcaconversations_lives";
    static final int MAX_DATES = 64;
    static final int MAX_BETRAYALS = 64;

    /** A date: where and when, and how it is going. Times are absolute overworld day time. */
    static final class Date {
        enum State { PLANNED, WAITING, ON, DONE }

        final UUID villager;
        final UUID player;
        final String villagerName;
        final BlockPos spot;
        final String place;
        final long start;
        State state = State.PLANNED;
        long stateSince;
        int turns;
        int warm;
        int cold;

        Date(UUID villager, UUID player, String villagerName, BlockPos spot, String place, long start) {
            this.villager = villager;
            this.player = player;
            this.villagerName = villagerName;
            this.spot = spot;
            this.place = place;
            this.start = start;
            this.stateSince = start;
        }

        CompoundTag toNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("v", villager);
            tag.putUUID("p", player);
            tag.putString("name", villagerName);
            tag.put("spot", NbtUtils.writeBlockPos(spot));
            tag.putString("place", place);
            tag.putLong("start", start);
            tag.putString("state", state.name());
            tag.putLong("since", stateSince);
            tag.putInt("turns", turns);
            tag.putInt("warm", warm);
            tag.putInt("cold", cold);
            return tag;
        }

        static Optional<Date> fromNbt(CompoundTag tag) {
            Optional<BlockPos> spot = NbtUtils.readBlockPos(tag, "spot");
            if (!tag.hasUUID("v") || !tag.hasUUID("p") || spot.isEmpty()) {
                return Optional.empty();
            }
            Date date = new Date(tag.getUUID("v"), tag.getUUID("p"), AiText.clean(tag.getString("name"), 64), spot.get(),
                    AiText.clean(tag.getString("place"), 64), tag.getLong("start"));
            try {
                date.state = State.valueOf(tag.getString("state"));
            } catch (IllegalArgumentException e) {
                date.state = State.DONE;
            }
            date.stateSince = tag.getLong("since");
            date.turns = tag.getInt("turns");
            date.warm = tag.getInt("warm");
            date.cold = tag.getInt("cold");
            return Optional.of(date);
        }
    }

    /** How a player treated a villager while they were a child. */
    static final class Childhood {
        int score;
        int gifts;
        boolean remembered;
    }

    /** A secret a player passed on; the one who confided it finds out at {@code due} (game time). */
    record Betrayal(UUID owner, UUID player, String listener, String summary, long due) {
    }

    private final List<Date> dates = new ArrayList<>();
    private final Map<String, Childhood> childhood = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> skills = new HashMap<>();
    private final Map<String, Long> taught = new HashMap<>();
    private final List<Betrayal> betrayals = new ArrayList<>();
    /** What each player has lent each villager: item id to count, by {@code villager/player}. */
    private final Map<String, Map<String, Integer>> loans = new HashMap<>();

    public static AiLivesSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(AiLivesSavedData::new, AiLivesSavedData::load, null), DATA_NAME);
    }

    static String key(UUID villager, UUID player) {
        return villager + "/" + player;
    }

    void changed() {
        setDirty();
    }

    // --- dates ---------------------------------------------------------------------------------------

    List<Date> dates() {
        return dates;
    }

    Optional<Date> date(UUID villager, UUID player) {
        return dates.stream().filter(d -> d.villager.equals(villager) && d.player.equals(player)
                && d.state != Date.State.DONE).findFirst();
    }

    void addDate(Date date) {
        dates.removeIf(d -> d.villager.equals(date.villager) && d.player.equals(date.player));
        dates.add(date);
        while (dates.size() > MAX_DATES) {
            dates.remove(0);
        }
        setDirty();
    }

    // --- childhood -----------------------------------------------------------------------------------

    Optional<Childhood> childhood(UUID villager, UUID player) {
        return Optional.ofNullable(childhood.get(key(villager, player)));
    }

    Childhood editChildhood(UUID villager, UUID player) {
        setDirty();
        return childhood.computeIfAbsent(key(villager, player), k -> new Childhood());
    }

    /** Every player who knew this villager as a child. */
    Map<UUID, Childhood> childhoodOf(UUID villager) {
        Map<UUID, Childhood> out = new HashMap<>();
        String prefix = villager + "/";
        childhood.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                try {
                    out.put(UUID.fromString(k.substring(prefix.length())), v);
                } catch (IllegalArgumentException ignored) {
                    // a malformed key is skipped
                }
            }
        });
        return out;
    }

    // --- skills --------------------------------------------------------------------------------------

    int skillXp(UUID villager, String chore) {
        return skills.getOrDefault(villager, Map.of()).getOrDefault(chore, 0);
    }

    Map<String, Integer> skills(UUID villager) {
        return Map.copyOf(skills.getOrDefault(villager, Map.of()));
    }

    void addSkillXp(UUID villager, String chore, int xp) {
        if (xp <= 0) {
            return;
        }
        skills.computeIfAbsent(villager, k -> new HashMap<>()).merge(chore, xp, (a, b) -> Math.min(10_000, a + b));
        setDirty();
    }

    /** True (and recorded) the first time today this villager is taught this task. */
    boolean claimTeaching(UUID villager, String chore, long day) {
        String k = villager + "/" + chore;
        Long last = taught.get(k);
        if (last != null && last == day) {
            return false;
        }
        taught.put(k, day);
        setDirty();
        return true;
    }

    // --- loans ---------------------------------------------------------------------------------------

    void addLoan(UUID villager, UUID player, String item, int count) {
        if (count <= 0) {
            return;
        }
        loans.computeIfAbsent(key(villager, player), k -> new HashMap<>()).merge(item, count, Integer::sum);
        setDirty();
    }

    /** Marks up to {@code count} of {@code item} as returned; returns how many of them were on loan. */
    int returnLoan(UUID villager, UUID player, String item, int count) {
        Map<String, Integer> lent = loans.get(key(villager, player));
        if (lent == null || count <= 0) {
            return 0;
        }
        int held = lent.getOrDefault(item, 0);
        int back = Math.min(held, count);
        if (back > 0) {
            if (held - back <= 0) {
                lent.remove(item);
            } else {
                lent.put(item, held - back);
            }
            if (lent.isEmpty()) {
                loans.remove(key(villager, player));
            }
            setDirty();
        }
        return back;
    }

    /**
     * A lent item that no longer exists (worn out, burned): settled with {@code preferred} first, then
     * with any other player who lent this villager the same thing. Returns how many were on loan.
     */
    int writeOffLoan(UUID villager, UUID preferred, String item, int count) {
        int settled = preferred == null ? 0 : returnLoan(villager, preferred, item, count);
        String prefix = villager + "/";
        for (String k : new ArrayList<>(loans.keySet())) {
            if (settled >= count || !k.startsWith(prefix)) {
                continue;
            }
            try {
                settled += returnLoan(villager, UUID.fromString(k.substring(prefix.length())), item, count - settled);
            } catch (IllegalArgumentException ignored) {
                // a malformed key is skipped
            }
        }
        return settled;
    }

    Map<String, Integer> loans(UUID villager, UUID player) {
        return Map.copyOf(loans.getOrDefault(key(villager, player), Map.of()));
    }

    // --- secrets -------------------------------------------------------------------------------------

    List<Betrayal> betrayals() {
        return betrayals;
    }

    void addBetrayal(Betrayal betrayal) {
        boolean known = betrayals.stream().anyMatch(b -> b.owner().equals(betrayal.owner())
                && b.player().equals(betrayal.player()));
        if (known) {
            return; // they will find out once; telling more people does not hurt twice as fast
        }
        betrayals.add(betrayal);
        while (betrayals.size() > MAX_BETRAYALS) {
            betrayals.remove(0);
        }
        setDirty();
    }

    void removeVillager(UUID villager) {
        boolean changed = dates.removeIf(d -> d.villager.equals(villager));
        changed |= betrayals.removeIf(b -> b.owner().equals(villager));
        changed |= skills.remove(villager) != null;
        String prefix = villager + "/";
        changed |= loans.keySet().removeIf(k -> k.startsWith(prefix));
        changed |= childhood.keySet().removeIf(k -> k.startsWith(prefix));
        changed |= taught.keySet().removeIf(k -> k.startsWith(prefix));
        if (changed) {
            setDirty();
        }
    }

    // --- saving --------------------------------------------------------------------------------------

    private static AiLivesSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        AiLivesSavedData data = new AiLivesSavedData();
        ListTag dateList = tag.getList("dates", Tag.TAG_COMPOUND);
        for (int i = 0; i < dateList.size(); i++) {
            Date.fromNbt(dateList.getCompound(i)).ifPresent(data.dates::add);
        }
        CompoundTag kids = tag.getCompound("childhood");
        for (String k : kids.getAllKeys()) {
            CompoundTag c = kids.getCompound(k);
            Childhood childhood = new Childhood();
            childhood.score = c.getInt("score");
            childhood.gifts = c.getInt("gifts");
            childhood.remembered = c.getBoolean("remembered");
            data.childhood.put(k, childhood);
        }
        CompoundTag skillTag = tag.getCompound("skills");
        for (String villager : skillTag.getAllKeys()) {
            try {
                UUID id = UUID.fromString(villager);
                CompoundTag chores = skillTag.getCompound(villager);
                Map<String, Integer> map = new HashMap<>();
                chores.getAllKeys().forEach(c -> map.put(c, chores.getInt(c)));
                data.skills.put(id, map);
            } catch (IllegalArgumentException ignored) {
                // skipped
            }
        }
        CompoundTag taughtTag = tag.getCompound("taught");
        taughtTag.getAllKeys().forEach(k -> data.taught.put(k, taughtTag.getLong(k)));
        CompoundTag loanTag = tag.getCompound("loans");
        for (String k : loanTag.getAllKeys()) {
            CompoundTag items = loanTag.getCompound(k);
            Map<String, Integer> map = new HashMap<>();
            items.getAllKeys().forEach(i -> map.put(i, items.getInt(i)));
            data.loans.put(k, map);
        }
        ListTag betrayalList = tag.getList("betrayals", Tag.TAG_COMPOUND);
        for (int i = 0; i < betrayalList.size(); i++) {
            CompoundTag b = betrayalList.getCompound(i);
            if (b.hasUUID("owner") && b.hasUUID("player")) {
                data.betrayals.add(new Betrayal(b.getUUID("owner"), b.getUUID("player"),
                        AiText.clean(b.getString("listener"), 64), AiText.clean(b.getString("summary"), AiText.MAX_MEMORY),
                        b.getLong("due")));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag dateList = new ListTag();
        dates.forEach(d -> dateList.add(d.toNbt()));
        tag.put("dates", dateList);
        CompoundTag kids = new CompoundTag();
        childhood.forEach((k, c) -> {
            CompoundTag t = new CompoundTag();
            t.putInt("score", c.score);
            t.putInt("gifts", c.gifts);
            t.putBoolean("remembered", c.remembered);
            kids.put(k, t);
        });
        tag.put("childhood", kids);
        CompoundTag skillTag = new CompoundTag();
        skills.forEach((id, chores) -> {
            CompoundTag t = new CompoundTag();
            chores.forEach(t::putInt);
            skillTag.put(id.toString(), t);
        });
        tag.put("skills", skillTag);
        CompoundTag taughtTag = new CompoundTag();
        taught.forEach(taughtTag::putLong);
        tag.put("taught", taughtTag);
        ListTag betrayalList = new ListTag();
        for (Betrayal b : betrayals) {
            CompoundTag t = new CompoundTag();
            t.putUUID("owner", b.owner());
            t.putUUID("player", b.player());
            t.putString("listener", b.listener());
            t.putString("summary", b.summary());
            t.putLong("due", b.due());
            betrayalList.add(t);
        }
        tag.put("betrayals", betrayalList);
        CompoundTag loanTag = new CompoundTag();
        loans.forEach((k, items) -> {
            CompoundTag t = new CompoundTag();
            items.forEach(t::putInt);
            loanTag.put(k, t);
        });
        tag.put("loans", loanTag);
        return tag;
    }
}
