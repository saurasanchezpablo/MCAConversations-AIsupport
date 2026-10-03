package dev.otectus.mcaconversations.mixin;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.ai.AiConversations;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * MCA's Talk button. Pressing it sends {@code InteractionDialogueInitMessage}, which opens MCA's
 * scripted dialogue tree at its "root" question (chat, jokes, stories, flirting and the rest). With
 * {@code ai.aiOnly} on, that tree never opens: the villager greets the player through the AI instead
 * and the conversation continues in typed chat. Off, MCA's dialogue opens untouched.
 *
 * <p>No {@code @Shadow}: the villager's UUID is read through the record's own public accessor, so the
 * mixin names no MCA type. {@code require = 0}: a renamed method leaves MCA's dialogue working.
 */
@Pseudo
@Mixin(targets = "net.conczin.mca.network.c2s.InteractionDialogueInitMessage", remap = false)
public abstract class InteractionDialogueInitMixin {

    @Inject(method = "handleServer", at = @At("HEAD"), cancellable = true, require = 0)
    private void mcaconversations$aiOnly(ServerPlayer player, CallbackInfo ci) {
        try {
            if (!AiConversations.aiOnly()) {
                return;
            }
            Object id = this.getClass().getMethod("villagerUUID").invoke(this);
            if (id instanceof UUID villager) {
                AiConversations.onTalkButton(player, villager);
                ci.cancel();
            }
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("AI-only Talk interception failed; MCA's dialogue opens instead", t);
        }
    }
}
