package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * The gift window: one row of slots above the player's own inventory. The player picks what to give
 * from anywhere in their inventory and drops it in; when the window closes, each stack is offered to
 * the villager through MCA's own gift handling (the same rules, reactions, hearts and special items
 * such as rings and bouquets as MCA's gift button), one item per stack as MCA takes it. Whatever the
 * villager does not take goes straight back to the player.
 *
 * <p>A vanilla one-row chest screen, so the client needs nothing new.
 */
final class AiGiftMenu extends ChestMenu {

    static final int SLOTS = 9;

    private final Entity villager;
    private final SimpleContainer gifts;
    private boolean settled;

    private AiGiftMenu(int id, Inventory inventory, SimpleContainer gifts, Entity villager) {
        super(MenuType.GENERIC_9x1, id, inventory, gifts, 1);
        this.villager = villager;
        this.gifts = gifts;
    }

    /** Opens the gift window for this villager. */
    static void open(ServerPlayer player, Entity villager) {
        String name = McaCompat.getVillagerName(villager).orElse(villager.getName().getString());
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new AiGiftMenu(id, inventory,
                new SimpleContainer(SLOTS), villager), Component.translatable("mcaconversations.ai.gift_title", name)));
    }

    @Override
    public boolean stillValid(Player player) {
        return villager.isAlive() && player.distanceTo(villager) <= 8;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (settled || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        settled = true;
        for (int slot = 0; slot < gifts.getContainerSize(); slot++) {
            ItemStack stack = gifts.removeItemNoUpdate(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack left = villager.isAlive() ? offer(serverPlayer, stack) : stack;
            if (!left.isEmpty()) {
                serverPlayer.getInventory().placeItemBackInInventory(left);
            }
        }
    }

    /**
     * Offers one stack through MCA's gift command, which reads the player's main hand: the stack is
     * put there for the call and the player's real main-hand item restored afterwards. Returns what
     * was not taken.
     */
    private ItemStack offer(ServerPlayer player, ItemStack stack) {
        ItemStack held = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        try {
            McaHandles.runInteraction(villager, player, "gift");
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("gift offer failed; returning the item", t);
        } finally {
            ItemStack left = player.getMainHandItem();
            player.setItemInHand(InteractionHand.MAIN_HAND, held);
            stack = left;
        }
        return stack;
    }
}
