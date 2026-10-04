package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.conversation.DepthClass;
import dev.otectus.mcaconversations.progress.AffectionMath;
import dev.otectus.mcaconversations.progress.ReplayPolicy;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.raid.Raid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Threats to the village, and who stood up to them. Monsters a player kills in a village are seen by
 * the villagers around and counted. Villagers hurt or killed by monsters leave the village frightened
 * for days, and the morning after a bad night it meets to talk it over. A raid under way is known to
 * everyone. A Hero of the Village is thanked by each villager, once a day. Guards take it personally.
 * A friend may come over and ask the player to help keep them safe.
 */
final class AiThreats {

    static final int VILLAGE_RADIUS = 64;
    static final double WITNESS_RADIUS = 16;
    /** One witnessed kill remembered per villager and player in this long. */
    static final long WITNESS_COOLDOWN = 6_000;
    /** Days a village stays frightened after an attack. */
    static final long FEAR_DAYS = 3;
    static final int HERO_HEARTS = 2;

    private static final Map<String, Long> WITNESSED = new HashMap<>();

    private AiThreats() {
    }

    static boolean on() {
        return AiConversations.enabled() && AiVillageEvents.enabled();
    }

    private static OptionalInt villageAt(ServerLevel level, Entity entity) {
        return McaCompat.findNearestVillageId(level, entity.blockPosition(), VILLAGE_RADIUS);
    }

    private static String kind(Entity entity) {
        return AiContextFormat.words(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath());
    }

    /** Something died. */
    static void onDeath(LivingEntity victim, DamageSource source) {
        if (!on() || !(victim.level() instanceof ServerLevel level) || level.getServer() == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        Entity killer = source.getEntity();
        if (victim instanceof Enemy && killer instanceof ServerPlayer player) {
            OptionalInt village = villageAt(level, victim);
            if (village.isEmpty()) {
                return;
            }
            AiVillageLifeSavedData data = AiVillageLifeSavedData.get(server);
            data.census(AiVillageEvents.villageKey(level, village.getAsInt())).defenders.merge(player.getUUID(), 1, Integer::sum);
            data.changed();
            witness(server, level, victim, player);
        } else if (McaCompat.isMcaVillager(victim) && killer instanceof Enemy) {
            attacked(server, level, victim, killer, true);
        }
    }

    /** Something was hurt. */
    static void onHurt(LivingEntity victim, DamageSource source) {
        if (!on() || !(victim.level() instanceof ServerLevel level) || level.getServer() == null
                || !McaCompat.isMcaVillager(victim)) {
            return;
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof Enemy) {
            attacked(level.getServer(), level, victim, attacker, false);
        } else if (attacker instanceof ServerPlayer player) {
            AiChildhood.onHurt(level.getServer(), victim, player);
        }
    }

    private static void witness(MinecraftServer server, ServerLevel level, LivingEntity monster, ServerPlayer player) {
        long now = level.getGameTime();
        long day = AffectionMath.dayOf(now);
        WITNESSED.values().removeIf(t -> now - t > WITNESS_COOLDOWN);
        for (Entity villager : level.getEntities(monster, monster.getBoundingBox().inflate(WITNESS_RADIUS),
                e -> e.isAlive() && McaCompat.isMcaVillager(e))) {
            String key = villager.getUUID() + "/" + player.getUUID();
            if (WITNESSED.containsKey(key) || !(villager instanceof LivingEntity seer) || !seer.hasLineOfSight(monster)) {
                continue;
            }
            WITNESSED.put(key, now);
            AiMemorySavedData.get(server).edit(villager.getUUID(), player.getUUID()).remember(new AiMemoryNote(
                    "I saw " + player.getName().getString() + " kill a " + kind(monster) + " that was prowling near our homes.",
                    AiImportance.LOW), AiSentiment.POSITIVE, day, Math.max(1, McaConversationsConfig.aiMemoriesPerPair()));
        }
    }

    private static void attacked(MinecraftServer server, ServerLevel level, LivingEntity villager, Entity attacker, boolean died) {
        OptionalInt village = McaCompat.getHomeVillageId(villager);
        if (village.isEmpty()) {
            village = villageAt(level, villager);
        }
        if (village.isEmpty()) {
            return;
        }
        AiVillageLifeSavedData data = AiVillageLifeSavedData.get(server);
        AiVillageLifeSavedData.Census census = data.census(AiVillageEvents.villageKey(level, village.getAsInt()));
        long today = Math.floorDiv(server.overworld().getDayTime(), AiVillageEventType.DAY);
        if (census.attackDay != today) {
            census.attackDay = today;
            census.attacks = 0;
            census.deaths = 0;
        }
        census.attacks++;
        if (died) {
            census.deaths++;
        }
        census.attacker = kind(attacker);
        data.changed();
        boolean defence = AiPolitics.platform(census).equals("defence");
        if (died || census.attacks == 3 || (defence && census.attacks == 1)) {
            AiVillageEvents.scheduleMeeting(server, level, village.getAsInt(), census.attacker);
        }
    }

    /** What the villager knows of danger to the village, and of this player's part in it. */
    static List<String> promptLines(MinecraftServer server, Entity villager, ServerPlayer player) {
        List<String> out = new ArrayList<>();
        if (!on() || !(villager.level() instanceof ServerLevel level)) {
            return out;
        }
        String name = player.getName().getString();
        Raid raid = level.getRaidAt(villager.blockPosition());
        if (raid != null && !raid.isOver()) {
            out.add("A RAID is happening RIGHT NOW: pillagers are attacking the village! You are terrified.");
        }
        if (player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)) {
            out.add(name + " is a Hero of the Village: they helped drive off the raid. You are deeply grateful.");
        }
        OptionalInt village = McaCompat.getHomeVillageId(villager);
        if (village.isEmpty()) {
            return out;
        }
        AiVillageLifeSavedData.Census census = AiVillageLifeSavedData.get(server)
                .census(AiVillageEvents.villageKey(level, village.getAsInt()));
        long today = Math.floorDiv(server.overworld().getDayTime(), AiVillageEventType.DAY);
        if (census.attackDay >= 0 && today - census.attackDay <= FEAR_DAYS) {
            long ago = today - census.attackDay;
            out.add("Your village was attacked by " + census.attacker + "s " + (ago == 0 ? "today" : ago == 1 ? "yesterday"
                    : ago + " days ago") + (census.deaths > 0 ? " and " + census.deaths + " of you died" : "")
                    + ". People are frightened and talk of little else.");
            if (guard(villager)) {
                out.add("You are a guard: you take the attacks personally, and you talk of patrols, watches and walls.");
            }
        }
        int kills = census.defenders.getOrDefault(player.getUUID(), 0);
        if (kills > 0) {
            out.add(name + " has killed " + kills + " monster" + (kills == 1 ? "" : "s") + " in your village. People have noticed.");
        }
        return out;
    }

    private static boolean guard(Entity villager) {
        return McaCompat.getProfessionId(villager).map(p -> p.contains("guard") || p.contains("archer")).orElse(false);
    }

    /** A friend asks for help after an attack. */
    static java.util.Optional<String> askReason(MinecraftServer server, Entity villager, String playerName) {
        if (!on() || !(villager.level() instanceof ServerLevel level)) {
            return java.util.Optional.empty();
        }
        OptionalInt village = McaCompat.getHomeVillageId(villager);
        if (village.isEmpty()) {
            return java.util.Optional.empty();
        }
        AiVillageLifeSavedData.Census census = AiVillageLifeSavedData.get(server)
                .census(AiVillageEvents.villageKey(level, village.getAsInt()));
        long today = Math.floorDiv(server.overworld().getDayTime(), AiVillageEventType.DAY);
        if (census.attackDay < 0 || today - census.attackDay > 1) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of("the village was attacked by " + census.attacker + "s and you want to ask " + playerName
                + " to help keep it safe");
    }

    /** A Hero of the Village is thanked by each villager, once a day. */
    static void heroThanks(MinecraftServer server, Entity villager, ServerPlayer player, long gameNow) {
        if (!on() || !player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE) || !McaConversationsConfig.aiRelationshipEffects()) {
            return;
        }
        long day = AffectionMath.dayOf(gameNow);
        AiHearts.Grant grant = AiHearts.grant(server, villager, player, "ai.hero", HERO_HEARTS, DepthClass.STANDARD,
                ReplayPolicy.ONCE, 0, 0, "ai.hero." + day + "." + villager.getUUID(), gameNow);
        if (grant.granted() > 0) {
            AiMemorySavedData.get(server).edit(villager.getUUID(), player.getUUID()).remember(new AiMemoryNote(
                    player.getName().getString() + " helped save our village from the raid.", AiImportance.HIGH),
                    AiSentiment.STRONGLY_POSITIVE, day, Math.max(1, McaConversationsConfig.aiMemoriesPerPair()));
        }
    }

    static void reset() {
        WITNESSED.clear();
    }
}
