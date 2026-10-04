package dev.otectus.mcaconversations.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One village life event: what it is, where and when, who it is for, and which players were told of
 * it and came. Times are absolute overworld day time. Server thread only.
 *
 * <p>{@code subjects}/{@code names} hold who it is about: for a funeral the mourners (names: the one
 * who died first), for a wedding the couple, for a birth the child then the parents, for a welcome the
 * newcomer, for a quarrel the two who fell out.
 */
final class AiVillageEvent {

    final int id;
    final AiVillageEventType type;
    final String dimension;
    final int villageId;
    final BlockPos spot;
    final String place;
    final long start;
    final long end;
    final UUID organizer;
    final List<UUID> subjects;
    final List<String> names;
    final String cause;
    final Set<UUID> invited = new LinkedHashSet<>();
    final Set<UUID> attended = new LinkedHashSet<>();
    boolean started;
    boolean ended;

    AiVillageEvent(int id, AiVillageEventType type, String dimension, int villageId, BlockPos spot, String place, long start,
                   long end, UUID organizer, List<UUID> subjects, List<String> names, String cause) {
        this.id = id;
        this.type = type;
        this.dimension = dimension;
        this.villageId = villageId;
        this.spot = spot;
        this.place = place == null ? "" : place;
        this.start = start;
        this.end = end;
        this.organizer = organizer;
        this.subjects = List.copyOf(subjects);
        this.names = List.copyOf(names);
        this.cause = cause == null ? "" : cause;
    }

    boolean active(long now) {
        return now >= start && now < end;
    }

    boolean upcoming(long now) {
        return now < start;
    }

    String name(int index) {
        return index < names.size() ? names.get(index) : "someone";
    }

    /** What the event is, in a villager's words, for prompts. Pure. */
    String describe() {
        String at = place.isEmpty() ? "" : " at the " + place;
        return switch (type) {
            case FESTIVAL -> "a festival" + at;
            case HARVEST_FEAST -> "a harvest feast" + at;
            case MARKET -> "a market day" + at;
            case FUNERAL -> "the funeral of " + name(0) + at;
            case WEDDING -> "the wedding of " + name(0) + " and " + name(1) + at;
            case BIRTH -> "a celebration for the birth of " + name(0) + at;
            case WELCOME -> "a welcome for " + name(0) + ", who has just moved in" + at;
            case QUARREL -> name(0) + " and " + name(1) + " falling out over " + (cause.isEmpty() ? "something" : cause);
        };
    }

    /** When, relative to {@code now}, in words. Pure. */
    String when(long now) {
        long today = Math.floorDiv(now, AiVillageEventType.DAY);
        if (active(now)) {
            return "right now";
        }
        if (upcoming(now)) {
            long day = Math.floorDiv(start, AiVillageEventType.DAY);
            long hour = Math.floorMod(start, AiVillageEventType.DAY);
            String part = hour < 6_000 ? "morning" : hour < 10_000 ? "afternoon" : "evening";
            if (day == today) {
                return "this " + part;
            }
            return day == today + 1 ? "tomorrow " + part : "in " + (day - today) + " days";
        }
        long ago = today - Math.floorDiv(end, AiVillageEventType.DAY);
        return ago <= 0 ? "earlier today" : ago == 1 ? "yesterday" : ago + " days ago";
    }

    /** Whether this event is about this villager (mourner, bride, parent, newcomer, one of a quarrel). */
    boolean about(UUID villager) {
        return subjects.contains(villager);
    }

    CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("type", type.key());
        tag.putString("dim", dimension);
        tag.putInt("village", villageId);
        tag.put("spot", NbtUtils.writeBlockPos(spot));
        tag.putString("place", place);
        tag.putLong("start", start);
        tag.putLong("end", end);
        if (organizer != null) {
            tag.putUUID("organizer", organizer);
        }
        tag.put("subjects", uuids(subjects));
        ListTag nameList = new ListTag();
        names.forEach(n -> nameList.add(StringTag.valueOf(n)));
        tag.put("names", nameList);
        tag.putString("cause", cause);
        tag.put("invited", uuids(invited));
        tag.put("attended", uuids(attended));
        tag.putBoolean("started", started);
        tag.putBoolean("ended", ended);
        return tag;
    }

    static Optional<AiVillageEvent> fromNbt(CompoundTag tag) {
        Optional<AiVillageEventType> type = AiVillageEventType.byKey(tag.getString("type"));
        Optional<BlockPos> spot = NbtUtils.readBlockPos(tag, "spot");
        if (type.isEmpty() || spot.isEmpty()) {
            return Optional.empty();
        }
        List<String> names = new ArrayList<>();
        ListTag nameList = tag.getList("names", Tag.TAG_STRING);
        for (int i = 0; i < nameList.size(); i++) {
            names.add(AiText.clean(nameList.getString(i), 64));
        }
        AiVillageEvent event = new AiVillageEvent(tag.getInt("id"), type.get(), tag.getString("dim"), tag.getInt("village"),
                spot.get(), AiText.clean(tag.getString("place"), 64), tag.getLong("start"), tag.getLong("end"),
                tag.hasUUID("organizer") ? tag.getUUID("organizer") : null, readUuids(tag, "subjects"), names,
                AiText.clean(tag.getString("cause"), AiText.MAX_MEMORY));
        event.invited.addAll(readUuids(tag, "invited"));
        event.attended.addAll(readUuids(tag, "attended"));
        event.started = tag.getBoolean("started");
        event.ended = tag.getBoolean("ended");
        return Optional.of(event);
    }

    static ListTag uuids(java.util.Collection<UUID> ids) {
        ListTag list = new ListTag();
        ids.forEach(id -> list.add(NbtUtils.createUUID(id)));
        return list;
    }

    static List<UUID> readUuids(CompoundTag tag, String key) {
        List<UUID> out = new ArrayList<>();
        ListTag list = tag.getList(key, Tag.TAG_INT_ARRAY);
        for (Tag t : list) {
            try {
                out.add(NbtUtils.loadUUID(t));
            } catch (IllegalArgumentException ignored) {
                // a malformed entry is dropped
            }
        }
        return out;
    }
}
