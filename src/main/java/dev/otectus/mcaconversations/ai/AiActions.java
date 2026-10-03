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
import java.util.Map;

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

    static void apply(Entity villager, ServerPlayer player, String villagerName, List<AiEffect.Action> actions,
                      AiSocial.Turn turn, long now) {
        for (AiEffect.Action action : actions) {
            try {
                apply(villager, player, villagerName, action, turn, now);
            } catch (Throwable t) {
                McaConversations.LOGGER.debug("AI action {} failed; skipped", action.kind(), t);
            }
        }
    }

    private static void apply(Entity villager, ServerPlayer player, String villagerName, AiEffect.Action action,
                              AiSocial.Turn turn, long now) {
        List<Entity> helpers = helpers(villager, player, action, turn);
        if (!helpers.isEmpty()) {
            group(villager, player, villagerName, action, turn, helpers, now);
            return;
        }
        switch (action.kind()) {
            case TRADE -> later(villager, player, "trade", now);
            case INVENTORY -> later(villager, player, "inventory", now);
            // The player chooses what to give, from their whole inventory, in the gift window.
            case GIFT -> AiTasks.schedule(now + SCREEN_DELAY_TICKS, () -> {
                if (villager.isAlive() && !player.hasDisconnected() && villager.distanceTo(player) <= 8) {
                    AiGiftMenu.open(player, villager);
                }
            });
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
                AiErrands.stop(villager.getUUID());
                McaHandles.runInteraction(villager, player, "stopworking");
            }
            case GUIDE, WAIT_AT, PICK_UP, STORE, FETCH, BREED -> AiErrands.start(villager, player, action, turn, villagerName, now);
            case GIVE -> give(villager, player, action.item(), Math.max(1, action.amount()));
        }
    }

    /** The other villagers this action brings in, resolved from exactly the names the model was shown. */
    static List<Entity> helpers(Entity leader, ServerPlayer player, AiEffect.Action action, AiSocial.Turn turn) {
        if (action.helpers().isEmpty() || turn == null) {
            return List.of();
        }
        List<Entity> out = new java.util.ArrayList<>();
        for (Map.Entry<String, java.util.UUID> h : turn.helperIds().entrySet()) {
            boolean named = action.everyone() || action.helpers().stream().anyMatch(n -> n.equalsIgnoreCase(h.getKey()));
            Entity e = named ? player.serverLevel().getEntity(h.getValue()) : null;
            if (e != null && e != leader && e.isAlive() && e.distanceTo(player) <= AiActionContext.HELPER_RANGE * 1.5) {
                out.add(e);
            }
        }
        return out;
    }

    /** Several villagers at once: each helper says it is coming, then everyone does the thing. */
    private static void group(Entity leader, ServerPlayer player, String leaderName, AiEffect.Action action,
                              AiSocial.Turn turn, List<Entity> helpers, long now) {
        List<Entity> everyone = new java.util.ArrayList<>();
        everyone.add(leader);
        everyone.addAll(helpers);
        List<Entity> unable = List.of();
        switch (action.kind()) {
            case WORK -> {
                AiChore chore = action.chore().orElse(null);
                if (chore == null) {
                    return;
                }
                unable = AiWork.startGroup(everyone, player, chore, action.amount(), now);
                for (int i = 0; i < unable.size(); i++) {
                    Entity e = unable.get(i);
                    AiLines.sayLater(e, player, AiLines.variant("work_no_tool",
                                    Component.translatable("mcaconversations.ai.tool." + chore.key())), name(e), now,
                            25L * (i + 1), AiEmotion.NEUTRAL, VoiceIntent.STATEMENT);
                }
            }
            case PICK_UP, BREED -> {
                for (Entity e : everyone) {
                    AiErrands.start(e, player, action, turn, name(e), e == leader, now);
                }
            }
            case FOLLOW, STAY, MOVE, GO_HOME -> {
                String command = switch (action.kind()) {
                    case FOLLOW -> "FOLLOW";
                    case STAY -> "STAY";
                    case MOVE -> "MOVE";
                    default -> "gohome";
                };
                for (Entity e : everyone) {
                    if (action.kind() != AiActionKind.STAY) {
                        VillagerAttention.release(e);
                    }
                    McaHandles.runInteraction(e, player, command);
                }
            }
            default -> apply(leader, player, leaderName, new AiEffect.Action(action.kind(), action.chore(), action.amount(),
                    action.item(), action.place()), turn, now);
        }
        int i = 0;
        for (Entity helper : helpers) {
            if (!unable.contains(helper)) {
                AiLines.sayLater(helper, player, AiLines.variant("group_join"), name(helper), now, 20L * (++i),
                        AiEmotion.HAPPY, VoiceIntent.STATEMENT);
            }
        }
    }

    private static String name(Entity e) {
        return dev.otectus.mcaconversations.compat.McaCompat.getVillagerName(e).orElse(e.getName().getString());
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
