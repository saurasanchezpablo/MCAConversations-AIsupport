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
import java.util.function.Predicate;

/**
 * A window for handing things over for a job: one or more rows of slots above the player's
 * inventory. Used for "what should I cook?" and "what do I build it with?". When it closes, whatever
 * was put in goes to the job; if the job cannot start, it all goes straight back to the player. A
 * vanilla chest screen, so the client needs nothing new.
 */
final class AiHandoverMenu extends ChestMenu {

    private final Entity villager;
    private final SimpleContainer items;
    /** Takes the handed stacks; false when the job could not start (the stacks are then returned). */
    private final Predicate<List<ItemStack>> onClose;
    private boolean settled;

    private AiHandoverMenu(int id, Inventory inventory, SimpleContainer items, int rows, Entity villager,
                           Predicate<List<ItemStack>> onClose) {
        super(rows == 1 ? MenuType.GENERIC_9x1 : rows == 2 ? MenuType.GENERIC_9x2 : MenuType.GENERIC_9x3, id, inventory,
                items, rows);
        this.villager = villager;
        this.items = items;
        this.onClose = onClose;
    }

    static void open(ServerPlayer player, Entity villager, String titleKey, int rows, Predicate<List<ItemStack>> onClose) {
        String name = McaCompat.getVillagerName(villager).orElse(villager.getName().getString());
        int r = Math.max(1, Math.min(3, rows));
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new AiHandoverMenu(id, inventory,
                new SimpleContainer(9 * r), r, villager, onClose), Component.translatable(titleKey, name)));
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
        boolean started = false;
        try {
            started = villager.isAlive() && onClose.test(handed);
        } finally {
            if (!started) {
                handed.forEach(stack -> serverPlayer.getInventory().placeItemBackInInventory(stack));
            }
        }
    }
}
