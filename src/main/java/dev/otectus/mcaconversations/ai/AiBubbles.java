package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import dev.otectus.mcaconversations.conversation.AgeGroup;
import dev.otectus.mcaconversations.network.ConversationsNetwork;
import dev.otectus.mcaconversations.network.VillagerBubblesS2C;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides which villagers around each player have something to tell them, for the bubble the client
 * draws over their heads: the same reasons that make a villager come over on their own
 * ({@link AiInitiative#reason}), without small talk and without the chance roll. A villager the player
 * has just talked with keeps quiet for a while; an invitation shows over the organiser only. Sent only
 * when a player's set changes. Server thread only.
 */
final class AiBubbles {

    static final int INTERVAL = 60;
    static final double RADIUS = 24.0;
    static final int MAX_CHECKED = 16;
    /** After talking with the player, a villager's bubble stays down this long. */
    static final long QUIET_AFTER_TALK = 6_000;

    private static final Map<UUID, Map<UUID, Byte>> SENT = new HashMap<>();
    private static final Map<String, Long> TALKED = new HashMap<>();

    private AiBubbles() {
    }

    static void talked(UUID villager, UUID player, long now) {
        TALKED.put(villager + "/" + player, now);
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % INTERVAL != 0) {
            return;
        }
        long now = server.overworld().getGameTime();
        TALKED.values().removeIf(t -> now - t > QUIET_AFTER_TALK);
        boolean on = McaConversationsConfig.aiBubbles();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Map<UUID, Byte> bubbles = on && !player.isSpectator() ? bubbles(server, player, now) : Map.of();
            Map<UUID, Byte> before = SENT.getOrDefault(player.getUUID(), Map.of());
            if (!bubbles.equals(before)) {
                SENT.put(player.getUUID(), bubbles);
                ConversationsNetwork.sendBubbles(player, new VillagerBubblesS2C(bubbles));
            }
        }
    }

    private static Map<UUID, Byte> bubbles(MinecraftServer server, ServerPlayer player, long now) {
        Map<UUID, Byte> out = new LinkedHashMap<>();
        Optional<UUID> partner = AiConversations.partner(player.getUUID(), now);
        List<Entity> around = player.serverLevel().getEntities(player, player.getBoundingBox().inflate(RADIUS),
                        e -> e.isAlive() && McaCompat.isMcaVillager(e)).stream()
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(player))).limit(MAX_CHECKED).toList();
        for (Entity villager : around) {
            if (partner.map(villager.getUUID()::equals).orElse(false) || TALKED.containsKey(villager.getUUID() + "/" + player.getUUID())
                    || !able(server, villager, player, now)) {
                continue;
            }
            try {
                AiInitiative.reason(AiInitiative.facts(server, villager, player, now, true))
                        .filter(r -> r.bubble() != AiInitiative.NO_BUBBLE && r.weight() >= 2)
                        .ifPresent(r -> out.put(villager.getUUID(), r.bubble()));
            } catch (Throwable t) {
                McaConversations.LOGGER.debug("bubble check failed for {}", villager.getUUID(), t);
            }
        }
        return out;
    }

    private static boolean able(MinecraftServer server, Entity villager, ServerPlayer player, long now) {
        AgeGroup age = McaCompat.ageGroup(villager);
        if (age == AgeGroup.BABY || age == AgeGroup.TODDLER || age == AgeGroup.UNKNOWN || McaHandles.silentVoice(villager)) {
            return false;
        }
        if (villager instanceof LivingEntity living && living.isSleeping()) {
            return false;
        }
        return AiMemorySavedData.get(server).peek(villager.getUUID(), player.getUUID())
                .map(pair -> !pair.grudge(now)).orElse(true);
    }

    static void forgetPlayer(UUID player) {
        SENT.remove(player);
    }

    static void reset() {
        SENT.clear();
        TALKED.clear();
    }
}
