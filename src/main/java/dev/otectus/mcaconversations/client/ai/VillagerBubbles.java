package dev.otectus.mcaconversations.client.ai;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.network.VillagerBubblesS2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.UUID;

/**
 * Draws a small bubble over a villager who has something to tell this player, from the set the
 * server sends ({@link VillagerBubblesS2C}): a gold "!" for something that matters, a white "…" for
 * news, a note for an invitation to a village event. It bobs gently and is drawn full-bright above the
 * name plate, so it reads at a distance and in the dark.
 */
@EventBusSubscriber(modid = McaConversations.MOD_ID, value = Dist.CLIENT)
public final class VillagerBubbles {

    /** Beyond this the bubble is not drawn. */
    static final double RANGE = 32.0;
    private static final int FULL_BRIGHT = 0xF000F0;

    private static volatile Map<UUID, Byte> bubbles = Map.of();

    private VillagerBubbles() {
    }

    public static void onBubbles(VillagerBubblesS2C payload) {
        bubbles = payload.bubbles();
    }

    static String glyph(byte kind) {
        return switch (kind) {
            case VillagerBubblesS2C.URGENT -> "!";
            case VillagerBubblesS2C.EVENT -> "♪";
            case VillagerBubblesS2C.DATE -> "❤";
            default -> "…";
        };
    }

    static int colour(byte kind) {
        return switch (kind) {
            case VillagerBubblesS2C.URGENT -> 0xFFFFC23A;
            case VillagerBubblesS2C.EVENT -> 0xFF7FE0FF;
            case VillagerBubblesS2C.DATE -> 0xFFFF6FAE;
            default -> 0xFFFFFFFF;
        };
    }

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Post<?, ?> event) {
        Map<UUID, Byte> current = bubbles;
        if (current.isEmpty()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        Byte kind = current.get(entity.getUUID());
        Minecraft mc = Minecraft.getInstance();
        if (kind == null || mc.player == null || mc.options.hideGui || !McaConversationsConfig.showVillagerBubbles()
                || entity.isInvisible() || entity.distanceTo(mc.player) > RANGE) {
            return;
        }
        float time = entity.tickCount + event.getPartialTick();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(0.0, entity.getBbHeight() + 0.95 + Math.sin(time / 8.0) * 0.06, 0.0);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        float scale = 0.045f;
        pose.scale(scale, -scale, scale);
        Matrix4f matrix = pose.last().pose();
        Font font = mc.font;
        Component text = Component.literal(glyph(kind));
        float x = -font.width(text) / 2f;
        font.drawInBatch(text, x, 0, colour(kind), false, matrix, event.getMultiBufferSource(),
                Font.DisplayMode.NORMAL, 0x60000000, FULL_BRIGHT);
        pose.popPose();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        bubbles = Map.of();
    }
}
