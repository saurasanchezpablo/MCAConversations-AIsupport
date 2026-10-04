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
            case WORK -> action.chore().ifPresent(chore -> work(villager, player, villagerName, chore, action.amount(), now));
            case STOP_WORK -> {
                AiWork.stop(villager.getUUID(), true);
                AiErrands.stop(villager.getUUID());
                McaHandles.runInteraction(villager, player, "stopworking");
            }
            case GUIDE, WAIT_AT, PICK_UP, STORE, FETCH, BREED -> AiErrands.start(villager, player, action, turn, villagerName, now);
            // The player puts what to cook (and any fuel) in the cooking window; the errand starts when it closes.
            case COOK -> AiTasks.schedule(now + SCREEN_DELAY_TICKS, () -> {
                if (villager.isAlive() && !player.hasDisconnected() && villager.distanceTo(player) <= 8) {
                    AiHandoverMenu.open(player, villager, "mcaconversations.ai.cook_title", 1, handed ->
                            AiErrands.startCook(villager, player, handed, villagerName, player.serverLevel().getGameTime()));
                }
            });
            case GIVE -> handOver(villager, player, villagerName, action.item(), action.amount(), now);
            case BUILD -> openBuild(villager, player, action, List.of(), now);
            case DATE -> AiDates.agree(villager, player, villagerName, action, turn, now);
        }
    }

    /**
     * Sends the villager to work. Without the tool for it, they say so and a window opens to lend them
     * one; the moment they have it, they get going.
     */
    static void work(Entity villager, ServerPlayer player, String villagerName, AiChore chore, int amount, long now) {
        if (AiWork.start(villager, player, chore, amount, villagerName, now)) {
            return;
        }
        if (AiWork.hasTool(villager, chore)) {
            return; // MCA refused the chore itself; nothing more to do here
        }
        AiWork.awaitTool(villager, player, chore, amount, now);
        AiLines.sayLater(villager, player, AiLines.variant("work_no_tool",
                        Component.translatable("mcaconversations.ai.tool." + chore.key())), villagerName, now, 20,
                AiEmotion.NEUTRAL, VoiceIntent.QUESTION);
        AiTasks.schedule(now + SCREEN_DELAY_TICKS + 20, () -> {
            if (villager.isAlive() && !player.hasDisconnected() && villager.distanceTo(player) <= 8) {
                AiHandoverMenu.open(player, villager, "mcaconversations.ai.tool_title", 1,
                        handed -> lend(villager, player, villagerName, handed));
            }
        });
    }

    /**
     * Puts what the player hands over straight into the villager's own inventory (a loan, not a gift
     * MCA would consume), and starts the job they were waiting for if they now have the tool. Whatever
     * does not fit is given back. Returns true once anything was taken.
     */
    static boolean lend(Entity villager, ServerPlayer player, String villagerName, List<ItemStack> handed) {
        Container inventory = McaHandles.inventory(villager);
        if (inventory == null) {
            return false;
        }
        boolean took = false;
        for (ItemStack stack : handed) {
            int before = stack.getCount();
            ItemStack rest = AiErrands.insert(inventory, stack.copy());
            took |= rest.getCount() < before;
            if (!rest.isEmpty()) {
                player.getInventory().placeItemBackInInventory(rest);
            }
        }
        inventory.setChanged();
        long now = player.serverLevel().getGameTime();
        if (!AiWork.resumeIfReady(villager, player, villagerName, now)) {
            AiWork.awaiting(villager.getUUID()).ifPresentOrElse(chore -> AiLines.say(villager, player,
                            AiLines.variant("work_no_tool", Component.translatable("mcaconversations.ai.tool." + chore.key())),
                            villagerName, AiEmotion.NEUTRAL, VoiceIntent.STATEMENT),
                    () -> AiLines.say(villager, player, AiLines.variant("tool_thanks"), villagerName, AiEmotion.GRATEFUL,
                            VoiceIntent.THANK));
        }
        return true;
    }

    /**
     * "Give me what you gathered": a villager working for the player comes back with it; otherwise
     * they hand over what they carry, everything gathered ({@code all}) or the item named.
     */
    static void handOver(Entity villager, ServerPlayer player, String villagerName, String item, int amount, long now) {
        if ((item.isEmpty() || item.equals(AiIntent.ALL)) && AiWork.bringBack(villager, player, now)) {
            return;
        }
        int given = item.isEmpty() || item.equals(AiIntent.ALL) ? giveGathered(villager, player)
                : give(villager, player, item, Math.max(1, amount == 0 ? 64 : amount));
        if (given == 0) {
            AiLines.sayLater(villager, player, AiLines.variant("give_nothing"), villagerName, now, 20,
                    AiEmotion.NEUTRAL, VoiceIntent.STATEMENT);
        }
    }

    /** Hands over everything the villager gathered (what any task brings in), keeping their tools. */
    static int giveGathered(Entity villager, ServerPlayer player) {
        Container inventory = McaHandles.inventory(villager);
        if (inventory == null) {
            return 0;
        }
        int given = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || stack.isDamageableItem()) {
                continue;
            }
            boolean gathered = false;
            for (AiChore chore : AiChore.values()) {
                gathered |= AiWork.yieldOf(chore).test(stack);
            }
            if (!gathered) {
                continue;
            }
            ItemStack taken = inventory.removeItemNoUpdate(i);
            given += taken.getCount();
            if (!player.getInventory().add(taken) && !taken.isEmpty()) {
                player.drop(taken, false);
            }
        }
        inventory.setChanged();
        return given;
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
            case BUILD -> openBuild(leader, player, action, helpers, now);
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

    /** The materials window; the build starts when it closes, with whoever was brought in to help. */
    private static void openBuild(Entity leader, ServerPlayer player, AiEffect.Action action, List<Entity> helpers, long now) {
        AiTasks.schedule(now + SCREEN_DELAY_TICKS, () -> {
            if (leader.isAlive() && !player.hasDisconnected() && leader.distanceTo(player) <= 8) {
                AiHandoverMenu.open(player, leader, "mcaconversations.ai.build_title", 3, handed ->
                        AiBuild.start(leader, helpers.stream().filter(Entity::isAlive).toList(), player, action.item(), handed,
                                player.serverLevel().getGameTime()));
            }
        });
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

    /** Hands over up to {@code amount} of one item (or {@code #tag}) from the villager's own inventory. Nothing is created. */
    static int give(Entity villager, ServerPlayer player, String itemId, int amount) {
        Container inventory = McaHandles.inventory(villager);
        if (inventory == null || !AiPromises.itemExists(itemId)) {
            return 0;
        }
        int left = amount;
        for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !AiPromises.matches(itemId, stack)) {
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
