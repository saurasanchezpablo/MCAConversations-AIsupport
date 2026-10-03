package dev.otectus.mcaconversations.client.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.network.ConversationPartnerS2C;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.UUID;

/**
 * Shows the player who they are in an AI conversation with: a small "● Talking with Alice" in the
 * corner of the screen while the conversation is live (grey with "(far)" when the villager is out of
 * earshot), and "→ Alice" above the chat box while typing, so it is clear who will hear the message.
 */
public final class ConversationIndicator {

    /** Within this distance a typed line reaches the partner (AiChatRouter.PARTNER_RANGE). */
    static final double EARSHOT = 16.0;

    private static UUID villager;
    private static String name = "";
    private static long expiresAt;

    private ConversationIndicator() {
    }

    public static void onPartner(ConversationPartnerS2C payload) {
        if (payload.villager().isEmpty()) {
            villager = null;
            return;
        }
        villager = payload.villager().get();
        name = payload.name();
        expiresAt = System.currentTimeMillis() + payload.idleMillis();
    }

    private static boolean active() {
        if (villager != null && System.currentTimeMillis() > expiresAt) {
            villager = null; // the server's "ended" was lost; never show a stale partner
        }
        return villager != null;
    }

    /** Distance to the partner, or -1 when the villager is not loaded here. */
    private static double distance(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return -1;
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.getUUID().equals(villager)) {
                return entity.distanceTo(mc.player);
            }
        }
        return -1;
    }

    static void renderHud(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!active() || mc.options.hideGui || mc.screen instanceof ChatScreen) {
            return;
        }
        double d = distance(mc);
        boolean near = d >= 0 && d <= EARSHOT;
        Component text = Component.literal("● ").withStyle(near ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
                .append(Component.translatable("mcaconversations.ai.indicator", name)
                        .withStyle(near ? ChatFormatting.WHITE : ChatFormatting.GRAY))
                .append(near ? Component.empty() : Component.translatable("mcaconversations.ai.indicator_far")
                        .withStyle(ChatFormatting.DARK_GRAY));
        int x = 4;
        int y = 4;
        graphics.fill(x - 2, y - 2, x + mc.font.width(text) + 2, y + mc.font.lineHeight + 1, 0x60000000);
        graphics.drawString(mc.font, text, x, y, 0xFFFFFF, true);
    }

    @EventBusSubscriber(modid = McaConversations.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Layers {
        private Layers() {
        }

        @SubscribeEvent
        public static void onRegisterLayers(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.CHAT,
                    ResourceLocation.fromNamespaceAndPath(McaConversations.MOD_ID, "conversation_indicator"),
                    ConversationIndicator::renderHud);
        }
    }

    @EventBusSubscriber(modid = McaConversations.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {
        }

        /** "→ Alice" just above the chat input, while typing. */
        @SubscribeEvent
        public static void onChatRender(ScreenEvent.Render.Post event) {
            if (!(event.getScreen() instanceof ChatScreen) || !active()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            double d = distance(mc);
            boolean near = d >= 0 && d <= EARSHOT;
            Component text = Component.literal("→ " + name).withStyle(near ? ChatFormatting.GREEN : ChatFormatting.GRAY)
                    .append(near ? Component.empty() : Component.translatable("mcaconversations.ai.indicator_far")
                            .withStyle(ChatFormatting.DARK_GRAY));
            GuiGraphics g = event.getGuiGraphics();
            int y = event.getScreen().height - 26;
            g.fill(1, y - 2, 4 + mc.font.width(text) + 2, y + mc.font.lineHeight + 1, 0x80000000);
            g.drawString(mc.font, text, 4, y, 0xFFFFFF, true);
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            villager = null;
        }
    }
}
