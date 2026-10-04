package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.minecraft.server.level.ServerPlayer;

/** World events the AI layer listens to: monsters in the village, villagers hurt, children mistreated. */
@EventBusSubscriber(modid = McaConversations.MOD_ID)
public final class AiWorldEvents {

    private AiWorldEvents() {
    }

    /** A villager's inventory screen opened: note what is in the bag. */
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            try {
                AiBag.opened(player, event.getContainer());
            } catch (Throwable t) {
                McaConversations.LOGGER.debug("bag snapshot failed", t);
            }
        }
    }

    /** ...and closed: see what the player took or put in. */
    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AiBag.closed(player);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        try {
            AiThreats.onDeath(event.getEntity(), event.getSource());
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("AI threat bookkeeping failed", t);
        }
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        try {
            AiThreats.onHurt(event.getEntity(), event.getSource());
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("AI threat bookkeeping failed", t);
        }
    }
}
