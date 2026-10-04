package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import dev.otectus.mcaconversations.conversation.AgeGroup;
import dev.otectus.mcaconversations.conversation.DepthClass;
import dev.otectus.mcaconversations.progress.AffectionMath;
import dev.otectus.mcaconversations.progress.ReplayPolicy;
import dev.otectus.mcaconversations.state.ConversationState;
import dev.otectus.mcaconversations.state.StateTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Village life: festivals, market days, harvest feasts, funerals, weddings, births, welcomes for
 * newcomers, and neighbours falling out.
 *
 * <ul>
 *   <li>A village with a player in it plans something of its own most mornings (festival, market,
 *       harvest feast or a quarrel, by {@code villageEventChance}). A death brings a funeral the next
 *       day; a new couple, a birth or a newcomer is noticed by a census of the village and celebrated.</li>
 *   <li>Villagers hear of it: every AI conversation in the village knows what is coming, what is under
 *       way and what happened, and a villager may come over on their own to invite a player. The
 *       organiser carries a bubble until the player has been told.</li>
 *   <li>When it starts, villagers walk to the place (the inn, the plaza, the graveyard...) and gather
 *       round, with music notes, hearts or quiet as befits it; nearby players see a bar.</li>
 *   <li>A player who stays a while has come: hearts with those it was for and the guests near them,
 *       and they remember it. A market day sweetens the traders' prices.</li>
 *   <li>A quarrel leaves two neighbours thinking less of each other, which a player can mend by talking
 *       each of them round ({@link AiMediation}).</li>
 * </ul>
 *
 * <p>Times follow the day clock (overworld day time). Server thread only.
 */
final class AiVillageEvents {

    static final int CENSUS_INTERVAL = 200;
    static final int VILLAGE_RADIUS = 96;
    static final double GATHER_RADIUS = 96;
    static final double PRESENCE_RADIUS = 14;
    static final int ATTEND_TICKS = 400;
    static final double BAR_RADIUS = 64;
    static final int MAX_ATTENDEES = 20;
    static final int MAX_GUESTS_REWARDED = 6;
    /** The bar shows "soon" this long before a gathering. */
    static final long ANNOUNCE_LEAD = 3_000;
    /** Villagers invite players to what starts within this long. */
    static final long INVITE_LEAD = 12_000;
    static final List<String> QUARREL_CAUSES = List.of(
            "a fence moved onto their land", "a debt of bread never repaid", "who let the sheep into the wheat",
            "a rude remark at the well", "a borrowed axe returned broken", "the noise at night",
            "a game of cards that ended badly", "whose turn it was to fetch water", "a broken promise to help with the harvest",
            "some unkind gossip");

    private record Village(ServerLevel level, int id) {
        String key() {
            return level.dimension().location() + "|" + id;
        }
    }

    private record Spot(BlockPos pos, String label) {
    }

    /** An invitation a villager could give a player. */
    record Invite(int eventId, String text) {
    }

    /** What is happening now for an event, rebuilt after a restart. */
    private static final class Live {
        ServerBossEvent bar;
        final Map<UUID, BlockPos> slots = new LinkedHashMap<>();
        final Map<UUID, Integer> presence = new HashMap<>();
        long nextChatter;
    }

    private static final Map<Integer, Live> LIVE = new HashMap<>();

    private AiVillageEvents() {
    }

    static boolean enabled() {
        return McaConversationsConfig.aiVillageEvents();
    }

    // --- every tick ------------------------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        int tick = server.getTickCount();
        if (tick % 20 != 0) {
            return;
        }
        if (!enabled()) {
            if (!LIVE.isEmpty()) {
                reset();
            }
            return;
        }
        AiVillageLifeSavedData data = AiVillageLifeSavedData.get(server);
        long now = server.overworld().getDayTime();
        if (tick % CENSUS_INTERVAL == 0) {
            data.prune(now);
            for (Village village : activeVillages(server)) {
                try {
                    census(server, village, data, now);
                    plan(server, village, data, now);
                } catch (Throwable t) {
                    McaConversations.LOGGER.debug("village life census failed", t);
                }
            }
        }
        for (AiVillageEvent event : List.copyOf(data.events())) {
            if (event.ended) {
                continue;
            }
            try {
                step(server, data, event, now, tick);
            } catch (Throwable t) {
                McaConversations.LOGGER.debug("village event {} failed; ending it", event.id, t);
                finish(server, data, event);
            }
        }
    }

    /** The villages players are in or next to right now. */
    private static List<Village> activeVillages(MinecraftServer server) {
        Map<String, Village> out = new LinkedHashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) {
                continue;
            }
            ServerLevel level = player.serverLevel();
            OptionalInt id = McaCompat.findNearestVillageId(level, player.blockPosition(), VILLAGE_RADIUS);
            if (id.isPresent()) {
                Village village = new Village(level, id.getAsInt());
                out.putIfAbsent(village.key(), village);
            }
        }
        return List.copyOf(out.values());
    }

    // --- noticing what happens in a village ---------------------------------------------------------------

    /** Newcomers, births and new couples since the last look. The first look only records. */
    private static void census(MinecraftServer server, Village village, AiVillageLifeSavedData data, long now) {
        ServerLevel level = village.level();
        AiVillageLifeSavedData.Census census = data.census(village.key());
        Map<UUID, String> residents = McaCompat.villageResidentNames(level, village.id());
        if (residents.isEmpty()) {
            return;
        }
        List<Entity> loaded = McaCompat.loadedVillageResidents(level, village.id());
        if (!census.initialised) {
            census.initialised = true;
            census.residents.addAll(residents.keySet());
            loaded.forEach(e -> census.partners.put(e.getUUID(), McaCompat.getPartnerUuid(e).orElse(AiVillageLifeSavedData.NOBODY)));
            data.changed();
            return;
        }
        boolean changed = false;
        for (Map.Entry<UUID, String> resident : residents.entrySet()) {
            if (census.residents.add(resident.getKey())) {
                changed = true;
                newcomer(server, village, data, resident.getKey(), resident.getValue(), now);
            }
        }
        changed |= census.residents.retainAll(residents.keySet());
        for (Entity villager : loaded) {
            UUID partner = McaCompat.getPartnerUuid(villager).orElse(AiVillageLifeSavedData.NOBODY);
            UUID before = census.partners.put(villager.getUUID(), partner);
            if (!partner.equals(before)) {
                changed = true;
                if (before != null && !partner.equals(AiVillageLifeSavedData.NOBODY)) {
                    wedding(server, village, data, villager, partner, residents, now);
                }
            }
        }
        if (changed) {
            data.changed();
        }
    }

    private static void newcomer(MinecraftServer server, Village village, AiVillageLifeSavedData data, UUID id, String name,
                                 long now) {
        Entity entity = village.level().getEntity(id);
        AgeGroup age = entity == null ? AgeGroup.UNKNOWN : McaCompat.ageGroup(entity);
        if (age == AgeGroup.BABY || age == AgeGroup.TODDLER || age == AgeGroup.CHILD) {
            List<UUID> parents = McaCompat.getParents(village.level(), id);
            List<String> names = new ArrayList<>(List.of(name));
            Map<UUID, String> residents = McaCompat.villageResidentNames(village.level(), village.id());
            parents.forEach(p -> names.add(residents.getOrDefault(p, "")));
            schedule(server, village, data, AiVillageEventType.BIRTH, AiVillageEventType.BIRTH.startSoon(now, 2_400),
                    parents, names, "", parents.isEmpty() ? null : parents.get(0));
        } else {
            schedule(server, village, data, AiVillageEventType.WELCOME, AiVillageEventType.WELCOME.startSoon(now, 2_400),
                    List.of(id), List.of(name), "", null);
        }
    }

    private static void wedding(MinecraftServer server, Village village, AiVillageLifeSavedData data, Entity villager,
                                UUID partner, Map<UUID, String> residents, long now) {
        boolean known = data.events().stream().anyMatch(e -> e.type == AiVillageEventType.WEDDING
                && e.subjects.contains(villager.getUUID()) && e.subjects.contains(partner));
        if (known) {
            return; // the other half of the couple already brought it up
        }
        String name = McaCompat.getVillagerName(villager).orElse(villager.getName().getString());
        ServerPlayer player = server.getPlayerList().getPlayer(partner);
        String partnerName = player != null ? player.getName().getString()
                : residents.getOrDefault(partner, McaCompat.getSpouseName(villager).orElse("their sweetheart"));
        schedule(server, village, data, AiVillageEventType.WEDDING, AiVillageEventType.WEDDING.startSoon(now, 2_400),
                List.of(villager.getUUID(), partner), List.of(name, partnerName), "", villager.getUUID());
    }

    /** Most mornings, a village with a player in it plans something of its own. */
    private static void plan(MinecraftServer server, Village village, AiVillageLifeSavedData data, long now) {
        AiVillageLifeSavedData.Census census = data.census(village.key());
        long today = Math.floorDiv(now, AiVillageEventType.DAY);
        long hour = Math.floorMod(now, AiVillageEventType.DAY);
        if (census.plannedDay >= today || hour > 9_000) {
            return;
        }
        census.plannedDay = today;
        data.changed();
        boolean busy = data.events().stream().anyMatch(e -> e.villageId == village.id() && !e.ended
                && e.type != AiVillageEventType.QUARREL && Math.floorDiv(e.start, AiVillageEventType.DAY) == today);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (busy || random.nextDouble() >= McaConversationsConfig.aiVillageEventChance()) {
            return;
        }
        AiVillageEventType type = AiVillageEventType.pickPlanned(random.nextDouble());
        if (type == AiVillageEventType.QUARREL) {
            quarrel(server, village, data, now);
            return;
        }
        type.startToday(now, 1_200).ifPresent(start -> schedule(server, village, data, type, start, List.of(), List.of(), "", null));
    }

    private static void quarrel(MinecraftServer server, Village village, AiVillageLifeSavedData data, long now) {
        ServerLevel level = village.level();
        List<Entity> adults = new ArrayList<>(McaCompat.loadedVillageResidents(level, village.id()).stream()
                .filter(e -> e.isAlive() && McaCompat.ageGroup(e) == AgeGroup.ADULT).toList());
        java.util.Collections.shuffle(adults);
        for (int i = 0; i < adults.size(); i++) {
            for (int j = i + 1; j < adults.size(); j++) {
                Entity a = adults.get(i);
                Entity b = adults.get(j);
                if (AiSocial.relationTo(level, a.getUUID(), b.getUUID()).isPresent()
                        || McaCompat.getPartnerUuid(a).map(b.getUUID()::equals).orElse(false)) {
                    continue; // family rows are another story
                }
                String cause = QUARREL_CAUSES.get(ThreadLocalRandom.current().nextInt(QUARREL_CAUSES.size()));
                String nameA = McaCompat.getVillagerName(a).orElse(a.getName().getString());
                String nameB = McaCompat.getVillagerName(b).orElse(b.getName().getString());
                schedule(server, village, data, AiVillageEventType.QUARREL, now + 600, List.of(a.getUUID(), b.getUUID()),
                        List.of(nameA, nameB), cause, null);
                return;
            }
        }
    }

    /** A villager died: the funeral is the next day, for those who loved them. */
    static void onDeath(MinecraftServer server, Entity deceased) {
        if (!enabled() || !(deceased.level() instanceof ServerLevel level)) {
            return;
        }
        OptionalInt villageId = McaCompat.getHomeVillageId(deceased);
        if (villageId.isEmpty()) {
            return;
        }
        UUID id = deceased.getUUID();
        Set<UUID> mourners = new java.util.LinkedHashSet<>();
        McaCompat.getPartnerFromTree(level, id).ifPresent(mourners::add);
        mourners.addAll(McaCompat.getParents(level, id));
        mourners.addAll(McaCompat.getChildren(level, id));
        mourners.addAll(McaCompat.getSiblings(level, id));
        mourners.remove(id);
        String name = McaCompat.getVillagerName(deceased).orElse(deceased.getName().getString());
        long now = server.overworld().getDayTime();
        long start = (Math.floorDiv(now, AiVillageEventType.DAY) + 1) * AiVillageEventType.DAY
                + AiVillageEventType.FUNERAL.startTime();
        AiVillageLifeSavedData data = AiVillageLifeSavedData.get(server);
        Village village = new Village(level, villageId.getAsInt());
        schedule(server, village, data, AiVillageEventType.FUNERAL, start, List.copyOf(mourners), List.of(name), "",
                mourners.isEmpty() ? null : mourners.iterator().next(), deceased.blockPosition());
        data.removeVillager(id);
    }

    private static AiVillageEvent schedule(MinecraftServer server, Village village, AiVillageLifeSavedData data,
                                           AiVillageEventType type, long start, List<UUID> subjects, List<String> names,
                                           String cause, UUID organizer) {
        return schedule(server, village, data, type, start, subjects, names, cause, organizer, null);
    }

    private static AiVillageEvent schedule(MinecraftServer server, Village village, AiVillageLifeSavedData data,
                                           AiVillageEventType type, long start, List<UUID> subjects, List<String> names,
                                           String cause, UUID organizer, BlockPos fallback) {
        ServerLevel level = village.level();
        Optional<Spot> spot;
        if (type == AiVillageEventType.QUARREL) {
            Entity a = level.getEntity(subjects.get(0));
            spot = a == null ? Optional.empty() : Optional.of(new Spot(a.blockPosition(), ""));
        } else {
            spot = spot(level, village.id(), type);
            if (spot.isEmpty() && fallback != null) {
                spot = Optional.of(new Spot(ground(level, fallback), "village square"));
            }
        }
        if (spot.isEmpty()) {
            return null;
        }
        if (organizer == null && type != AiVillageEventType.QUARREL) {
            organizer = organiser(level, village.id(), type, subjects).orElse(null);
        }
        AiVillageEvent event = new AiVillageEvent(data.nextId(), type, level.dimension().location().toString(), village.id(),
                spot.get().pos(), spot.get().label(), start, start + type.duration(), organizer, subjects, names, cause);
        data.add(event);
        if (McaConversationsConfig.debugAi()) {
            McaConversations.LOGGER.info("[ai] village {} plans {} ({}), {} at {}", village.id(), type.key(), event.describe(),
                    event.when(server.overworld().getDayTime()), spot.get().pos());
        }
        return event;
    }

    private static Optional<UUID> organiser(ServerLevel level, int villageId, AiVillageEventType type, List<UUID> subjects) {
        List<Entity> adults = McaCompat.loadedVillageResidents(level, villageId).stream()
                .filter(e -> e.isAlive() && McaCompat.ageGroup(e) == AgeGroup.ADULT && !subjects.contains(e.getUUID()))
                .toList();
        if (type == AiVillageEventType.MARKET) {
            Optional<Entity> trader = adults.stream().filter(e -> e instanceof Villager v
                    && v.getVillagerData().getProfession() != VillagerProfession.NONE
                    && v.getVillagerData().getProfession() != VillagerProfession.NITWIT).findAny();
            if (trader.isPresent()) {
                return trader.map(Entity::getUUID);
            }
        }
        return adults.isEmpty() ? Optional.empty()
                : Optional.of(adults.get(ThreadLocalRandom.current().nextInt(adults.size())).getUUID());
    }

    /** Where to hold a gathering: the first of its preferred buildings the village has, else its middle. */
    private static Optional<Spot> spot(ServerLevel level, int villageId, AiVillageEventType type) {
        Map<String, BlockPos> buildings = McaHandles.villageBuildingCentres(level, villageId, null);
        for (String place : type.places()) {
            BlockPos pos = buildings.get(place);
            if (pos != null) {
                return Optional.of(new Spot(ground(level, pos), AiContextFormat.words(place)));
            }
        }
        if (buildings.isEmpty()) {
            return Optional.empty();
        }
        long x = 0;
        long y = 0;
        long z = 0;
        for (BlockPos pos : buildings.values()) {
            x += pos.getX();
            y += pos.getY();
            z += pos.getZ();
        }
        int n = buildings.size();
        return Optional.of(new Spot(ground(level, new BlockPos((int) (x / n), (int) (y / n), (int) (z / n))), "village square"));
    }

    /** A standable spot at or near {@code pos}: floor below, room for a villager above. */
    static BlockPos ground(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return pos;
        }
        for (int r = 0; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int dy = 3; dy >= -10; dy--) {
                        BlockPos p = pos.offset(dx, dy, dz);
                        if (level.isLoaded(p) && standable(level, p)) {
                            return p;
                        }
                    }
                }
            }
        }
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);
    }

    private static boolean standable(ServerLevel level, BlockPos p) {
        return level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)
                && level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                && level.getFluidState(p).isEmpty();
    }

    // --- running an event ------------------------------------------------------------------------------

    private static ServerLevel level(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private static void step(MinecraftServer server, AiVillageLifeSavedData data, AiVillageEvent event, long now, int tick) {
        ServerLevel level = level(server, event.dimension);
        if (level == null) {
            return;
        }
        if (now >= event.end) {
            finish(server, data, event);
            return;
        }
        if (now < event.start) {
            if (event.type.gathering() && event.start - now <= ANNOUNCE_LEAD) {
                bar(level, event, LIVE.computeIfAbsent(event.id, k -> new Live()), now);
            }
            return;
        }
        Live live = LIVE.computeIfAbsent(event.id, k -> new Live());
        if (!event.started) {
            event.started = true;
            data.changed();
            begin(server, level, event, now);
        }
        if (event.type == AiVillageEventType.QUARREL) {
            quarrelScene(level, event, live, now);
            return;
        }
        if (tick % 400 == 0 || live.slots.isEmpty()) {
            recruit(level, event, live, server.overworld().getGameTime());
        }
        gather(server, level, data, event, live, now, tick);
    }

    private static void begin(MinecraftServer server, ServerLevel level, AiVillageEvent event, long now) {
        if (event.type == AiVillageEventType.QUARREL) {
            UUID a = event.subjects.get(0);
            UUID b = event.subjects.get(1);
            long day = AffectionMath.dayOf(level.getGameTime());
            AiMemorySavedData memory = AiMemorySavedData.get(server);
            memory.adjustOpinion(a, b, event.name(1), "warmth", -2, event.cause, day);
            memory.adjustOpinion(a, b, event.name(1), "trust", -1, event.cause, day);
            memory.adjustOpinion(b, a, event.name(0), "warmth", -2, event.cause, day);
            memory.adjustOpinion(b, a, event.name(0), "respect", -1, event.cause, day);
            return;
        }
        MutableComponent line = Component.translatable("mcaconversations.ai.event.begun", title(event))
                .withStyle(ChatFormatting.GOLD);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(event.spot.getCenter()) <= GATHER_RADIUS * GATHER_RADIUS) {
                player.displayClientMessage(line, false);
            }
        }
    }

    /** Brings in the residents nearby who are free: those it is for first. */
    private static void recruit(ServerLevel level, AiVillageEvent event, Live live, long gameTime) {
        List<Entity> residents = new ArrayList<>(McaCompat.loadedVillageResidents(level, event.villageId));
        residents.sort(Comparator.comparing((Entity e) -> !event.about(e.getUUID()))
                .thenComparingDouble(e -> e.distanceToSqr(event.spot.getCenter())));
        for (Entity resident : residents) {
            if (live.slots.size() >= MAX_ATTENDEES) {
                break;
            }
            if (live.slots.containsKey(resident.getUUID()) || !free(resident, event, gameTime)
                    || resident.distanceToSqr(event.spot.getCenter()) > GATHER_RADIUS * GATHER_RADIUS) {
                continue;
            }
            live.slots.put(resident.getUUID(), slot(level, event.spot, live.slots.size()));
        }
    }

    private static boolean free(Entity villager, AiVillageEvent event, long gameTime) {
        AgeGroup age = McaCompat.ageGroup(villager);
        if (!villager.isAlive() || age == AgeGroup.BABY || age == AgeGroup.UNKNOWN
                || (villager instanceof LivingEntity l && l.isSleeping()) || McaCompat.isPanicking(villager)) {
            return false;
        }
        UUID id = villager.getUUID();
        if (AiWork.job(id).isPresent() || AiErrands.busy(id) || AiConversations.inConversation(id, gameTime)
                || McaCompat.isInteractingWith(villager).isPresent()) {
            return false;
        }
        // At work: only what it is for, or a sad or joyful family occasion, calls them away.
        boolean working = !McaCompat.getCurrentChore(villager).orElse("NONE").equalsIgnoreCase("NONE");
        return !working || event.about(id) || event.type == AiVillageEventType.FUNERAL
                || event.type == AiVillageEventType.WEDDING;
    }

    /** Places round the spot, in rings, so the gathering is a circle and not a pile. */
    private static BlockPos slot(ServerLevel level, BlockPos centre, int index) {
        int ring = index / 8;
        double radius = 2.5 + ring * 1.6;
        double angle = index * (Math.PI / 4) + ring * 0.4;
        BlockPos p = centre.offset((int) Math.round(Math.cos(angle) * radius), 0, (int) Math.round(Math.sin(angle) * radius));
        BlockPos stand = ground(level, p);
        return stand.distSqr(centre) > 100 ? centre : stand;
    }

    private static void gather(MinecraftServer server, ServerLevel level, AiVillageLifeSavedData data, AiVillageEvent event,
                               Live live, long now, int tick) {
        long gameTime = server.overworld().getGameTime();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Entity> present = new ArrayList<>();
        for (Map.Entry<UUID, BlockPos> slot : live.slots.entrySet()) {
            Entity villager = level.getEntity(slot.getKey());
            if (villager == null || !free(villager, event, gameTime)) {
                continue;
            }
            steer(villager, slot.getValue(), event.spot);
            if (villager.distanceToSqr(event.spot.getCenter()) <= 64) {
                present.add(villager);
            }
        }
        if (tick % 40 == 0) {
            celebrate(level, event, present, random);
        }
        bar(level, event, live, now);
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.distanceToSqr(event.spot.getCenter()) > PRESENCE_RADIUS * PRESENCE_RADIUS) {
                continue;
            }
            int stayed = live.presence.merge(player.getUUID(), 20, Integer::sum);
            if (stayed >= ATTEND_TICKS && event.attended.add(player.getUUID())) {
                data.changed();
                reward(server, level, event, live, player);
            }
        }
        if (now >= live.nextChatter && !present.isEmpty()) {
            live.nextChatter = now + 300 + random.nextInt(400);
            chatter(level, event, present.get(random.nextInt(present.size())));
        }
    }

    /** Walks a villager to its place through its own brain, and has it face the middle once there. */
    private static void steer(Entity entity, BlockPos target, BlockPos centre) {
        double distance = entity.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        if (entity instanceof Villager villager) {
            Brain<Villager> brain = villager.getBrain();
            if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
                if (distance > 2.25) {
                    brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, 0.6f, 1));
                } else {
                    brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                    brain.setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(centre.above()));
                }
                return;
            }
        }
        if (entity instanceof Mob mob) {
            if (distance > 2.25) {
                mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
            } else {
                mob.getLookControl().setLookAt(centre.getX() + 0.5, centre.getY() + 1.5, centre.getZ() + 0.5);
            }
        }
    }

    private static void release(ServerLevel level, Live live) {
        for (UUID id : live.slots.keySet()) {
            if (level.getEntity(id) instanceof Villager villager) {
                Brain<Villager> brain = villager.getBrain();
                brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
            }
        }
        live.slots.clear();
    }

    /** Music and dancing at a festival, hearts at a wedding, quiet at a funeral. */
    private static void celebrate(ServerLevel level, AiVillageEvent event, List<Entity> present, ThreadLocalRandom random) {
        ParticleOptions particle = switch (event.type) {
            case FESTIVAL, HARVEST_FEAST -> ParticleTypes.NOTE;
            case WEDDING -> ParticleTypes.HEART;
            case FUNERAL -> ParticleTypes.SOUL;
            default -> ParticleTypes.HAPPY_VILLAGER;
        };
        for (Entity villager : present) {
            if (random.nextInt(3) != 0) {
                continue;
            }
            level.sendParticles(particle, villager.getX(), villager.getY() + villager.getBbHeight() + 0.4, villager.getZ(),
                    1, 0.2, 0.1, 0.2, 0.0);
            if ((event.type == AiVillageEventType.FESTIVAL || event.type == AiVillageEventType.HARVEST_FEAST
                    || event.type == AiVillageEventType.WEDDING) && villager instanceof Mob mob && mob.onGround()) {
                mob.getJumpControl().jump(); // dancing
            }
        }
    }

    /** One villager says something fitting to the players close by. */
    private static void chatter(ServerLevel level, AiVillageEvent event, Entity speaker) {
        String name = McaCompat.getVillagerName(speaker).orElse(speaker.getName().getString());
        MutableComponent line = Component.literal(name + ": ").withStyle(ChatFormatting.YELLOW)
                .append(AiLines.variant("event." + event.type.key() + ".chatter", event.place, event.name(0), event.name(1))
                        .withStyle(ChatFormatting.WHITE));
        for (ServerPlayer player : level.players()) {
            if (player.distanceTo(speaker) <= 16) {
                player.displayClientMessage(line, false);
            }
        }
    }

    /** Two neighbours have it out: they meet, and the sharp words fly. */
    private static void quarrelScene(ServerLevel level, AiVillageEvent event, Live live, long now) {
        Entity a = level.getEntity(event.subjects.get(0));
        Entity b = level.getEntity(event.subjects.get(1));
        if (a == null || b == null || !a.isAlive() || !b.isAlive()) {
            return;
        }
        if (live.slots.isEmpty()) {
            live.slots.put(a.getUUID(), a.blockPosition());
            live.slots.put(b.getUUID(), b.blockPosition());
        }
        BlockPos middle = BlockPos.containing(a.position().add(b.position()).scale(0.5));
        if (a.distanceTo(b) > 3) {
            steer(a, middle, b.blockPosition());
            steer(b, middle, a.blockPosition());
            return;
        }
        if (a instanceof Mob ma) {
            ma.getLookControl().setLookAt(b);
        }
        if (b instanceof Mob mb) {
            mb.getLookControl().setLookAt(a);
        }
        if (now < live.nextChatter) {
            return;
        }
        live.nextChatter = now + 60;
        boolean first = ThreadLocalRandom.current().nextBoolean();
        Entity speaker = first ? a : b;
        String speakerName = first ? event.name(0) : event.name(1);
        String other = first ? event.name(1) : event.name(0);
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, speaker.getX(), speaker.getY() + speaker.getBbHeight() + 0.3,
                speaker.getZ(), 2, 0.2, 0.1, 0.2, 0.0);
        MutableComponent line = Component.literal(speakerName + ": ").withStyle(ChatFormatting.RED)
                .append(AiLines.variant("event.quarrel.shout", other, event.cause).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer player : level.players()) {
            if (player.distanceTo(speaker) <= 24) {
                player.displayClientMessage(line, false);
            }
        }
    }

    private static void finish(MinecraftServer server, AiVillageLifeSavedData data, AiVillageEvent event) {
        event.ended = true;
        data.changed();
        Live live = LIVE.remove(event.id);
        if (live == null) {
            return;
        }
        if (live.bar != null) {
            live.bar.removeAllPlayers();
        }
        ServerLevel level = level(server, event.dimension);
        if (level != null) {
            release(level, live);
        }
    }

    private static MutableComponent title(AiVillageEvent event) {
        return Component.translatable("mcaconversations.ai.event." + event.type.key(), event.place, event.name(0), event.name(1));
    }

    private static void bar(ServerLevel level, AiVillageEvent event, Live live, long now) {
        if (!event.type.gathering()) {
            return;
        }
        boolean active = event.active(now);
        if (live.bar == null) {
            live.bar = new ServerBossEvent(title(event), colour(event.type), BossEvent.BossBarOverlay.NOTCHED_10);
        }
        live.bar.setName(title(event).append(Component.literal(" - ")).append(Component.translatable(
                active ? "mcaconversations.ai.event.now" : "mcaconversations.ai.event.soon")));
        live.bar.setProgress(active ? Math.max(0f, 1f - (now - event.start) / (float) event.type.duration()) : 1f);
        for (ServerPlayer player : level.players()) {
            boolean near = player.distanceToSqr(event.spot.getCenter()) <= BAR_RADIUS * BAR_RADIUS;
            if (near) {
                live.bar.addPlayer(player);
            } else {
                live.bar.removePlayer(player);
            }
        }
    }

    private static BossEvent.BossBarColor colour(AiVillageEventType type) {
        return switch (type) {
            case FESTIVAL -> BossEvent.BossBarColor.PURPLE;
            case HARVEST_FEAST -> BossEvent.BossBarColor.YELLOW;
            case MARKET, WELCOME -> BossEvent.BossBarColor.GREEN;
            case FUNERAL -> BossEvent.BossBarColor.WHITE;
            case WEDDING -> BossEvent.BossBarColor.PINK;
            case BIRTH -> BossEvent.BossBarColor.BLUE;
            case QUARREL -> BossEvent.BossBarColor.RED;
        };
    }

    // --- coming ------------------------------------------------------------------------------------------

    /** The player came: hearts and a memory with those it was for and the guests around them. */
    private static void reward(MinecraftServer server, ServerLevel level, AiVillageEvent event, Live live, ServerPlayer player) {
        long gameTime = level.getGameTime();
        long day = AffectionMath.dayOf(gameTime);
        String playerName = player.getName().getString();
        AiMemorySavedData memory = AiMemorySavedData.get(server);
        int cap = McaConversationsConfig.aiMemoriesPerPair();
        Set<UUID> rewarded = new HashSet<>();
        for (UUID id : event.subjects) {
            Entity honouree = level.getEntity(id);
            if (honouree == null || !honouree.isAlive() || honouree.distanceTo(player) > 48) {
                continue;
            }
            rewarded.add(id);
            hearts(server, honouree, player, event, event.type.honoureeHearts(), gameTime);
            if (cap > 0) {
                memory.edit(id, player.getUUID()).remember(new AiMemoryNote(honourNote(event, playerName), AiImportance.HIGH),
                        AiSentiment.STRONGLY_POSITIVE, day, cap);
            }
            switch (event.type) {
                case FUNERAL, WELCOME -> StateTracker.apply(honouree, player, ConversationState.GRATEFUL);
                case WEDDING, BIRTH -> StateTracker.apply(honouree, player, ConversationState.ELATED);
                default -> {
                }
            }
        }
        List<Entity> guests = live.slots.keySet().stream().filter(id -> !rewarded.contains(id)).map(level::getEntity)
                .filter(e -> e != null && e.isAlive() && e.distanceTo(player) <= 20)
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(player))).limit(MAX_GUESTS_REWARDED).toList();
        for (Entity guest : guests) {
            hearts(server, guest, player, event, event.type.guestHearts(), gameTime);
            if (cap > 0) {
                memory.edit(guest.getUUID(), player.getUUID()).remember(new AiMemoryNote(guestNote(event, playerName),
                        AiImportance.LOW), AiSentiment.POSITIVE, day, cap);
            }
            if (event.type == AiVillageEventType.MARKET && guest instanceof Villager trader) {
                int granted = memory.edit(guest.getUUID(), player.getUUID())
                        .claimTradeMood(AiOutcomePlan.TRADE_BONUS, day, AiSocialEffects.TRADE_MOOD_DAILY_CAP);
                if (granted > 0) {
                    trader.getGossips().add(player.getUUID(), GossipType.MINOR_POSITIVE, granted);
                }
            }
        }
        player.displayClientMessage(Component.translatable("mcaconversations.ai.event.attended", title(event))
                .withStyle(ChatFormatting.GOLD), true);
    }

    private static void hearts(MinecraftServer server, Entity villager, ServerPlayer player, AiVillageEvent event, int hearts,
                               long gameTime) {
        if (hearts != 0 && McaConversationsConfig.aiRelationshipEffects()) {
            AiHearts.grant(server, villager, player, "ai.event." + event.type.key(), hearts, DepthClass.STANDARD,
                    ReplayPolicy.ONCE, 0, 0, "ai.event." + event.id + "." + villager.getUUID(), gameTime);
        }
    }

    /** What those it was for remember of the player coming. Pure. */
    static String honourNote(AiVillageEvent event, String player) {
        return switch (event.type) {
            case FUNERAL -> player + " came to " + event.name(0) + "'s funeral. It meant a great deal to me.";
            case WEDDING -> player + " came to our wedding.";
            case BIRTH -> player + " came to celebrate the birth of our child " + event.name(0) + ".";
            case WELCOME -> player + " came to welcome me to the village.";
            default -> guestNote(event, player);
        };
    }

    /** What a guest remembers of the player coming. Pure. */
    static String guestNote(AiVillageEvent event, String player) {
        return switch (event.type) {
            case FESTIVAL -> player + " came to the festival and we had a good time.";
            case HARVEST_FEAST -> player + " joined us at the harvest feast.";
            case MARKET -> player + " came to market day.";
            case FUNERAL -> player + " came to " + event.name(0) + "'s funeral.";
            case WEDDING -> player + " came to the wedding of " + event.name(0) + " and " + event.name(1) + ".";
            case BIRTH -> player + " came to celebrate " + event.name(0) + "'s birth.";
            case WELCOME -> player + " came to welcome " + event.name(0) + " to the village.";
            case QUARREL -> player + " saw " + event.name(0) + " and " + event.name(1) + " arguing.";
        };
    }

    // --- what villagers know and say ------------------------------------------------------------------------

    /**
     * A gathering this villager could invite the player to: in their village, starting within half a
     * day or under way, and the player neither told of it nor come yet.
     */
    static Optional<Invite> invitation(MinecraftServer server, Entity villager, String playerName, UUID player,
                                       boolean organiserOnly) {
        if (!enabled()) {
            return Optional.empty();
        }
        OptionalInt village = McaCompat.getHomeVillageId(villager);
        if (village.isEmpty()) {
            return Optional.empty();
        }
        long now = server.overworld().getDayTime();
        for (AiVillageEvent event : AiVillageLifeSavedData.get(server).events()) {
            if (event.ended || !event.type.gathering() || event.villageId != village.getAsInt()
                    || event.invited.contains(player) || event.attended.contains(player)
                    || !(event.active(now) || event.start - now <= INVITE_LEAD)
                    || (organiserOnly && !villager.getUUID().equals(event.organizer))) {
                continue;
            }
            String role = villager.getUUID().equals(event.organizer) ? " (you are organising it)"
                    : event.about(villager.getUUID()) ? " (it is for you and your family)" : "";
            return Optional.of(new Invite(event.id, "there is " + event.describe() + " " + event.when(now) + role
                    + " and you would like " + playerName + " to come"));
        }
        return Optional.empty();
    }

    static void markInvited(MinecraftServer server, int eventId, UUID player) {
        AiVillageLifeSavedData data = AiVillageLifeSavedData.get(server);
        data.events().stream().filter(e -> e.id == eventId).findFirst().ifPresent(e -> {
            if (e.invited.add(player)) {
                data.changed();
            }
        });
    }

    /**
     * Prompt lines about village life for one AI turn: what is coming, going on, or just happened in
     * the villager's village, and their part in it. Telling the villager to invite the player counts
     * as the player being told.
     */
    static List<String> promptLines(MinecraftServer server, Entity villager, ServerPlayer player) {
        List<String> out = new ArrayList<>();
        OptionalInt village = McaCompat.getHomeVillageId(villager);
        if (!enabled() || village.isEmpty()) {
            return out;
        }
        AiVillageLifeSavedData data = AiVillageLifeSavedData.get(server);
        long now = server.overworld().getDayTime();
        String playerName = player.getName().getString();
        UUID self = villager.getUUID();
        for (AiVillageEvent event : data.events()) {
            if (event.villageId != village.getAsInt()) {
                continue;
            }
            if (event.type == AiVillageEventType.QUARREL) {
                if (!event.started) {
                    continue;
                }
                if (event.about(self)) {
                    String other = event.subjects.get(0).equals(self) ? event.name(1) : event.name(0);
                    out.add("You fell out with " + other + " " + event.when(now) + " over " + event.cause
                            + ". You are still sore about it.");
                } else {
                    out.add("People are talking: " + event.describe() + " (" + event.when(now) + ").");
                }
                continue;
            }
            StringBuilder line = new StringBuilder();
            if (event.ended || now >= event.end) {
                line.append(capitalise(event.when(now))).append(" there was ").append(event.describe()).append('.');
                line.append(event.attended.contains(player.getUUID()) ? " " + playerName + " came."
                        : event.invited.contains(player.getUUID()) ? " " + playerName + " was invited but did not come." : "");
            } else {
                line.append(event.active(now) ? "Happening right now: " : "Coming up " + event.when(now) + ": ")
                        .append(event.describe()).append('.');
                if (self.equals(event.organizer)) {
                    line.append(" You are organising it.");
                }
                if (event.attended.contains(player.getUUID())) {
                    line.append(" ").append(playerName).append(" is here / came.");
                } else if (!event.invited.contains(player.getUUID())) {
                    line.append(" ").append(playerName).append(" may not know yet: tell them and invite them if it fits.");
                    event.invited.add(player.getUUID());
                    data.changed();
                }
            }
            if (event.about(self)) {
                line.append(switch (event.type) {
                    case FUNERAL -> " You are in mourning; it is your loved one's funeral.";
                    case WEDDING -> " It is YOUR wedding.";
                    case BIRTH -> " It is for YOUR new child.";
                    case WELCOME -> " It is for YOU: you have just moved here.";
                    default -> "";
                });
            }
            out.add(line.toString());
        }
        return out;
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static void reset() {
        LIVE.values().forEach(live -> {
            if (live.bar != null) {
                live.bar.removeAllPlayers();
            }
        });
        LIVE.clear();
    }
}
