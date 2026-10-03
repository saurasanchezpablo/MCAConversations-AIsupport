package dev.otectus.mcaconversations.mixin;

import dev.otectus.mcaconversations.ai.AiConversations;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A villager holding a grudge from an AI conversation will not trade with the player who caused it.
 *
 * <p>Every way an MCA villager's trade window opens ends in vanilla {@code Villager.startTrading}:
 * MCA's "trade" interaction, sneak-clicking, and MCA's chat-AI {@code open-trade-window} command. So
 * this one vanilla hook covers them all, and MCA itself is not touched. Vanilla villagers are never
 * refused: {@link AiConversations#refusesTrade} only answers for MCA villagers, and only while the AI
 * integration and its gameplay effects are on. {@code require = 0}: if the method is ever renamed,
 * trading simply stays unrestricted.
 */
@Mixin(Villager.class)
public abstract class VillagerTradeMixin {

    @Inject(method = "startTrading", at = @At("HEAD"), cancellable = true, require = 0)
    private void mcaconversations$refuseWhileHurt(Player player, CallbackInfo ci) {
        try {
            if (player instanceof ServerPlayer serverPlayer
                    && AiConversations.refusesTrade((Villager) (Object) this, serverPlayer)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Never break trading because of this hook.
        }
    }
}
