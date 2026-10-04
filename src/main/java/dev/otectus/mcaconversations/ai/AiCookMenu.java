package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.compat.McaCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The "what should I cook?" window: one row of slots above the player's inventory, where they put
 * what to cook or smelt and, if they like, the fuel. When it closes the villager takes it all to the
 * nearest furnace, smoker or blast furnace ({@link AiErrands#startCook}); if it cannot, everything goes
 * straight back to the player. A vanilla one-row chest screen, so the client needs nothing new.
 */
final class AiCookMenu extends ChestMenu {

    static final int SLOTS = 9;

    private final Entity villager;
    private final SimpleContainer items;
    private boolean settled;

    private AiCookMenu(int id, Inventory inventory, SimpleContainer items, Entity villager) {
        super(MenuType.GENERIC_9x1, id, inventory, items, 1);
        this.villager = villager;
        this.items = items;
    }

    static void open(ServerPlayer player, Entity villager) {
        String name = McaCompat.getVillagerName(villager).orElse(villager.getName().getString());
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new AiCookMenu(id, inventory,
                new SimpleContainer(SLOTS), villager), Component.translatable("mcaconversations.ai.cook_title", name)));
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
        List<ItemStack> handed = new ArrayList<>();
        for (int slot = 0; slot < items.getContainerSize(); slot++) {
            ItemStack stack = items.removeItemNoUpdate(slot);
            if (!stack.isEmpty()) {
                handed.add(stack);
            }
        }
        if (handed.isEmpty()) {
            return;
        }
        String name = McaCompat.getVillagerName(villager).orElse(villager.getName().getString());
        long now = serverPlayer.serverLevel().getGameTime();
        if (!villager.isAlive() || !AiErrands.startCook(villager, serverPlayer, handed, name, now)) {
            handed.forEach(stack -> serverPlayer.getInventory().placeItemBackInInventory(stack));
        }
    }
}
