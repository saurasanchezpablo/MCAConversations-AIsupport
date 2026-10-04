package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/** World events the AI layer listens to: monsters in the village, villagers hurt, children mistreated. */
@EventBusSubscriber(modid = McaConversations.MOD_ID)
public final class AiWorldEvents {

    private AiWorldEvents() {
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
