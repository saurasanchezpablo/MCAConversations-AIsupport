package dev.otectus.mcaconversations.client.voice;

import dev.otectus.mcaconversations.McaConversations;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Leaving a world stops every villager voice and drops directions meant for that world. */
@EventBusSubscriber(modid = McaConversations.MOD_ID, value = Dist.CLIENT)
public final class VoiceClientEvents {

    private VoiceClientEvents() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        VillagerVoices.INSTANCE.reset();
    }
}
