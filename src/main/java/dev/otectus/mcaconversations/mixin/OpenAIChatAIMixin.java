package dev.otectus.mcaconversations.mixin;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.ai.AiConversations;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The AI-conversation integration's one hook: MCA's OpenAI-compatible chat strategy hands its request
 * to {@link AiConversations} while {@code ai.enabled} is on, so the reply can carry structured
 * consequences. Everything around the request stays MCA's: which villager a chat line addresses, the
 * per-villager strategy (an Inworld character never reaches this class), the endpoint configuration,
 * and the delivery of the line through the villager's {@code ConversationManager}.
 *
 * <p><b>Two generations, one injector each.</b> On 1.21.1 (MCA 7.7.33 and 7.7.36-beta.3) the
 * strategy is synchronous: {@code Optional<String> answer(ServerPlayer, VillagerEntityMCA, String)},
 * called on a common-pool thread by MCA's {@code MixinServerGamePacketListenerImpl}. The asynchronous
 * {@code requestAndApply(ServerPlayer, VillagerEntityMCA, String, MinecraftServer)} is what MCA's Forge
 * 7.7.1 line moved to; its injector is kept, as a {@code require = 0} no-op today, so the 1.21.1 line
 * picking that change up keeps working. MixinTargetProbeTest requires exactly one of them per build.
 *
 * <p>{@link Pseudo} and {@link Coerce} for the villager parameter, for the reasons the other MCA
 * mixins give. {@code remap = false}: MCA's own methods. When the integration is
 * off, or the handler throws, MCA's original request runs untouched.
 */
@Pseudo
@Mixin(targets = "net.conczin.mca.entity.ai.chatAI.OpenAIChatAI", remap = false)
public abstract class OpenAIChatAIMixin {

    @Inject(method = "answer", at = @At("HEAD"), cancellable = true, require = 0)
    private void mcaconversations$answer(ServerPlayer player, @Coerce Object villager, String message,
                                         CallbackInfoReturnable<Optional<String>> cir) {
        try {
            if (AiConversations.enabled()) {
                cir.setReturnValue(AiConversations.answerBlocking(player, villager, message));
            }
        } catch (Throwable t) {
            McaConversations.LOGGER.error("AI conversations failed to take the request; MCA's chat AI answers instead", t);
        }
    }

    @Inject(method = "requestAndApply", at = @At("HEAD"), cancellable = true, require = 0)
    private void mcaconversations$requestAndApply(ServerPlayer player, @Coerce Object villager, String message,
                                                  MinecraftServer server,
                                                  CallbackInfoReturnable<CompletableFuture<Optional<String>>> cir) {
        try {
            if (AiConversations.enabled()) {
                cir.setReturnValue(AiConversations.answerAsync(player, villager, message));
            }
        } catch (Throwable t) {
            McaConversations.LOGGER.error("AI conversations failed to take the request; MCA's chat AI answers instead", t);
        }
    }
}
