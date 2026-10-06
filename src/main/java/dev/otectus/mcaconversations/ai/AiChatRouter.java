package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.compat.McaCompat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Typed chat to villagers, when AI conversations are on. The player's message is still ordinary chat
 * everyone sees; when it is addressed to a villager, that villager answers through the AI. Who it is
 * addressed to, in order:
 * <ol>
 *   <li>a villager named in the message (full or first name), the nearest if several;</li>
 *   <li>the villager the player is already talking with, while they are close and the talk is live;</li>
 *   <li>the villager the player is looking straight at, up close.</li>
 * </ol>
 * Otherwise the message is just chat. This is the only path to an AI reply: MCA's own chat-AI routing
 * is silenced while this is on, so a line is never answered twice.
 */
public final class AiChatRouter {

    static final double NAMED_RANGE = 24.0;
    static final double PARTNER_RANGE = 16.0;
    static final double LOOK_RANGE = 6.0;
    static final double LOOK_COS = 0.97;

    private AiChatRouter() {
    }

    public static void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        MinecraftServer server = player.getServer();
        String message = event.getRawText();
        if (server == null || message == null || message.isBlank() || message.startsWith("/")) {
            return;
        }
        server.execute(() -> target(player, message).ifPresent(v -> AiConversations.converse(player, v, message)));
    }

    static Optional<Entity> target(ServerPlayer player, String message) {
        if (player.isSpectator() || !player.isAlive()) {
            return Optional.empty();
        }
        // The box is only the broad phase: its corners reach ~42 blocks, past where a reply is heard.
        List<Entity> near = player.serverLevel().getEntities(player, player.getBoundingBox().inflate(NAMED_RANGE),
                e -> e.isAlive() && e.distanceToSqr(player) <= NAMED_RANGE * NAMED_RANGE && McaCompat.isMcaVillager(e));
        Comparator<Entity> nearest = Comparator.comparingDouble(e -> e.distanceToSqr(player));
        Optional<Entity> named = near.stream()
                .filter(e -> McaCompat.getVillagerName(e).map(n -> AiAddressing.mentions(message, n)).orElse(false))
                .min(nearest);
        if (named.isPresent()) {
            return named;
        }
        long now = player.level().getGameTime();
        Optional<UUID> partner = AiConversations.partner(player.getUUID(), now);
        if (partner.isPresent()) {
            Optional<Entity> live = near.stream().filter(e -> e.getUUID().equals(partner.get())
                    && e.distanceTo(player) <= PARTNER_RANGE).findFirst();
            if (live.isPresent()) {
                return live;
            }
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        return near.stream()
                .filter(e -> e.distanceTo(player) <= LOOK_RANGE)
                .filter(e -> {
                    Vec3 to = e.getEyePosition().subtract(eye).normalize();
                    return to.dot(look) >= LOOK_COS && (!(e instanceof LivingEntity l) || l.hasLineOfSight(player));
                })
                .min(nearest);
    }
}
