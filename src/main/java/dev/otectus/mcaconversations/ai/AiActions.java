package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.chat.VillagerAttention;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import dev.otectus.mcaconversations.voice.VoiceIntent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Carries out what a player asked a villager to do, by word: through MCA's own interaction commands
 * (the same ones its interaction screen sends), or through {@link AiWork} for tasks. Screens open a
 * moment after the villager's line, so the reply is read first.
 */
final class AiActions {

    /** A screen (trade, inventory) opens this long after the villager's line. */
    static final long SCREEN_DELAY_TICKS = 30;

    private AiActions() {
    }

    static void apply(Entity villager, ServerPlayer player, String villagerName, List<AiEffect.Action> actions, long now) {
        for (AiEffect.Action action : actions) {
            try {
                apply(villager, player, villagerName, action, now);
            } catch (Throwable t) {
                McaConversations.LOGGER.debug("AI action {} failed; skipped", action.kind(), t);
            }
        }
    }

    private static void apply(Entity villager, ServerPlayer player, String villagerName, AiEffect.Action action, long now) {
        switch (action.kind()) {
            case TRADE -> later(villager, player, "trade", now);
            case INVENTORY -> later(villager, player, "inventory", now);
            case GIFT -> McaHandles.runInteraction(villager, player, "gift");
            case FOLLOW -> {
                VillagerAttention.release(villager);
                McaHandles.runInteraction(villager, player, "FOLLOW");
            }
            case STAY -> McaHandles.runInteraction(villager, player, "STAY");
            case MOVE -> {
                VillagerAttention.release(villager);
                McaHandles.runInteraction(villager, player, "MOVE");
            }
            case GO_HOME -> {
                VillagerAttention.release(villager);
                McaHandles.runInteraction(villager, player, "gohome");
            }
            case ARMOR -> McaHandles.runInteraction(villager, player, "armor");
            case WORK -> action.chore().ifPresent(chore -> {
                if (!AiWork.start(villager, player, chore, action.amount(), villagerName, now)) {
                    AiLines.sayLater(villager, player, AiLines.variant("work_no_tool",
                            Component.translatable("mcaconversations.ai.tool." + chore.key())), villagerName, now, 20,
                            AiEmotion.NEUTRAL, VoiceIntent.STATEMENT);
                }
            });
            case STOP_WORK -> {
                AiWork.stop(villager.getUUID(), true);
                McaHandles.runInteraction(villager, player, "stopworking");
            }
            case GIVE -> give(villager, player, action.item(), Math.max(1, action.amount()));
        }
    }

    private static void later(Entity villager, ServerPlayer player, String command, long now) {
        AiTasks.schedule(now + SCREEN_DELAY_TICKS, () -> {
            if (villager.isAlive() && !player.hasDisconnected() && villager.distanceTo(player) <= 8) {
                McaHandles.runInteraction(villager, player, command);
            }
        });
    }

    /** Hands over up to {@code amount} of one item from the villager's own inventory. Nothing is created. */
    static int give(Entity villager, ServerPlayer player, String itemId, int amount) {
        Container inventory = McaHandles.inventory(villager);
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (inventory == null || id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return 0;
        }
        int left = amount;
        for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                continue;
            }
            ItemStack taken = stack.split(Math.min(left, stack.getCount()));
            left -= taken.getCount();
            if (!player.getInventory().add(taken) && !taken.isEmpty()) {
                player.drop(taken, false);
            }
        }
        inventory.setChanged();
        return amount - left;
    }
}
