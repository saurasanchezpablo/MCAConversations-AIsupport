package dev.otectus.mcaconversations.mixin;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.ai.AiConversations;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A right-click on an MCA villager. MCA's {@code interactAt} opens MCA's interaction screen (or, when
 * sneaking, the trade window). In talk-on-click mode a plain right-click starts an AI conversation
 * instead; sneak + right-click opens MCA's own screen, so nothing MCA offers becomes unreachable.
 * Babies and villagers MCA would not open a screen for (panicking, a blacklisted item in hand) are
 * left to MCA. Server side only; the client's half of the click is untouched.
 *
 * <p>{@code require = 0}: if MCA renames the method, MCA's menu simply keeps opening.
 */
@Pseudo
@Mixin(targets = "net.conczin.mca.entity.VillagerEntityMCA", remap = false)
public abstract class VillagerInteractMixin {

    @Inject(method = "interactAt", at = @At("HEAD"), cancellable = true, require = 0)
    private void mcaconversations$talkOnClick(Player player, Vec3 pos, InteractionHand hand,
                                              CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer) || hand != InteractionHand.MAIN_HAND
                    || !AiConversations.talkOnClick()) {
                return;
            }
            Entity villager = (Entity) (Object) this;
            if (!villager.isAlive() || McaHandles.silentVoice(villager) || villager.getVehicle() == player
                    || dev.otectus.mcaconversations.compat.McaCompat.isPanicking(villager)) {
                return;
            }
            if (player.isShiftKeyDown()) {
                // The way back to MCA's own screen: family tree, profession, work menus and the rest.
                if (McaHandles.openInteractScreen(villager, player, pos, hand)) {
                    cir.setReturnValue(InteractionResult.SUCCESS);
                }
                return;
            }
            AiConversations.onClicked(serverPlayer, villager);
            cir.setReturnValue(InteractionResult.SUCCESS);
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("talk-on-click failed; MCA's menu opens instead", t);
        }
    }
}
