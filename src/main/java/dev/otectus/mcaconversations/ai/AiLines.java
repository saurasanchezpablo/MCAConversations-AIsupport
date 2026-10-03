package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.compat.mca.McaHandles;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Lines a villager says outside the model's reply (a kept promise noticed, the way to a place, a
 * refusal). They go through the villager's own MCA message queue, so they come out exactly like a
 * chat-AI reply: the villager walks over and speaks in MCA's chat style.
 */
final class AiLines {

    /** Every keyed line has this many variants, {@code <key>.1} .. {@code <key>.3}. */
    static final int VARIANTS = 3;

    private AiLines() {
    }

    /** A random variant of {@code mcaconversations.ai.<key>.N}, with arguments. */
    static MutableComponent variant(String key, Object... args) {
        int n = 1 + ThreadLocalRandom.current().nextInt(VARIANTS);
        return Component.translatable("mcaconversations.ai." + key + "." + n, args);
    }

    /** As {@link #say(Entity, ServerPlayer, MutableComponent, String)}, voiced with this emotion and intent. */
    static void say(Entity villager, ServerPlayer player, MutableComponent line, String villagerName, AiEmotion emotion,
                    dev.otectus.mcaconversations.voice.VoiceIntent intent) {
        // A literal line is matched by its text; a translated one only exists in the client's language,
        // so its direction applies to the villager's next line instead.
        String text = line.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents.LiteralContents literal
                ? literal.text() : "";
        AiDelivery base = AiDelivery.from(emotion, AiSentiment.NEUTRAL);
        AiVoice.direct(player, villager, text, emotion,
                new AiDelivery(intent, base.tone(), base.pace(), base.intensity(), base.volume()), null);
        say(villager, player, line, villagerName);
    }

    /** Has the villager say {@code line} to the player; falls back to an action-bar line. */
    static void say(Entity villager, ServerPlayer player, MutableComponent line, String villagerName) {
        if (!McaHandles.queueMessage(villager, player, line)) {
            player.displayClientMessage(Component.literal(villagerName + ": ").append(line)
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    /**
     * As {@link #say}, but {@code delayTicks} later, so the line follows the villager's reply (which
     * MCA queues only once the turn completes) instead of jumping ahead of it. Dropped if either party
     * is gone by then.
     */
    static void sayLater(Entity villager, ServerPlayer player, MutableComponent line, String villagerName, long now,
                         long delayTicks) {
        sayLater(villager, player, line, villagerName, now, delayTicks, AiEmotion.NEUTRAL,
                dev.otectus.mcaconversations.voice.VoiceIntent.STATEMENT);
    }

    static void sayLater(Entity villager, ServerPlayer player, MutableComponent line, String villagerName, long now,
                         long delayTicks, AiEmotion emotion, dev.otectus.mcaconversations.voice.VoiceIntent intent) {
        AiTasks.schedule(now + delayTicks, () -> {
            if (villager.isAlive() && !villager.isRemoved() && !player.hasDisconnected()) {
                say(villager, player, line, villagerName, emotion, intent);
            }
        });
    }
}
