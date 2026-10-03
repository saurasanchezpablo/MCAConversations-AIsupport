package dev.otectus.mcaconversations.mixin.client;

import dev.otectus.mcaconversations.client.voice.VillagerVoices;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * MCA's client TTS entry point: every villager line MCA delivers passes through
 * {@code SpeechManager.onChatMessage(Component, UUID)} (from {@code ClientHandlerImpl.handleVillagerMessage})
 * on its way to MCA's speech engines. When this mod's acting voice is configured it voices the line
 * itself and MCA stays silent, so a line is never spoken twice; otherwise MCA proceeds untouched.
 * {@code require = 0}: if MCA renames the method, MCA's own TTS simply keeps working.
 */
@Pseudo
@Mixin(targets = "net.conczin.mca.client.tts.SpeechManager", remap = false)
public abstract class SpeechManagerMixin {

    @Inject(method = "onChatMessage", at = @At("HEAD"), cancellable = true, require = 0)
    private void mcaconversations$actingVoice(Component message, UUID sender, CallbackInfo ci) {
        try {
            if (VillagerVoices.INSTANCE.intercept(message, sender)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Never let the voice break MCA's chat; MCA's own TTS takes the line instead.
        }
    }
}
