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

    /**
     * Why something the villager said they would do did not happen: in words for the model, which
     * rewrites the line so it is true, and as a scripted line if that fails.
     */
    record Issue(String why, net.minecraft.network.chat.MutableComponent fallback) {
    }

    /** What became of one action: done (or under way, or a window opened for it), or not, and why. */
    record Result(AiActionKind kind, boolean done, Issue issue) {
        static Result ok(AiActionKind kind) {
            return new Result(kind, true, null);
        }

        static Result failed(AiActionKind kind, Issue issue) {
            return new Result(kind, false, issue);
        }
    }

    static List<Result> apply(Entity villager, ServerPlayer player, String villagerName, List<AiEffect.Action> actions,
                              AiSocial.Turn turn, long now) {
        List<Result> results = new java.util.ArrayList<>();
        for (AiEffect.Action action : actions) {
            try {
                results.add(apply(villager, player, villagerName, action, turn, now));
            } catch (Throwable t) {
                McaConversations.LOGGER.debug("AI action {} failed; skipped", action.kind(), t);
                results.add(Result.failed(action.kind(), new Issue("something went wrong and you could not do it",
                        AiLines.variant("cannot.generic"))));
            }
        }
        return results;
    }

    private static Result apply(Entity villager, ServerPlayer player, String villagerName, AiEffect.Action action,
                                AiSocial.Turn turn, long now) {
        List<Entity> helpers = helpers(villager, player, action, turn);
        if (!helpers.isEmpty()) {
            return group(villager, player, villagerName, action, turn, helpers, now);
        }
        AiActionKind kind = action.kind();
        switch (kind) {
            case TRADE -> later(villager, player, "trade", now);
            case INVENTORY -> later(villager, player, "inventory", now);
            // The player chooses what to give, from their whole inventory, in the gift window.
            case GIFT -> AiTasks.schedule(now + SCREEN_DELAY_TICKS, () -> {
                if (within(villager, player)) {
                    AiGiftMenu.open(player, villager);
                }
            });
            case FOLLOW -> {
                VillagerAttention.release(villager);
                McaHandles.runInteraction(villager, player, "FOLLOW");
            }
            case STAY -> {
                if (!McaHandles.runInteraction(villager, player, "STAY")) {
                    return Result.failed(kind, generic());
                }
            }
            case MOVE -> {
                VillagerAttention.release(villager);
                McaHandles.runInteraction(villager, player, "MOVE");
            }
            case GO_HOME -> {
                VillagerAttention.release(villager);
                McaHandles.runInteraction(villager, player, "gohome");
            }
            case ARMOR -> McaHandles.runInteraction(villager, player, "armor");
            case WORK -> {
                AiChore chore = action.chore().orElse(null);
                if (chore == null) {
                    return Result.failed(kind, generic());
                }
                Work work = work(villager, player, villagerName, chore, action.amount(), now, false);
                if (work == Work.NEEDS_TOOL) {
                    Component tool = Component.translatable("mcaconversations.ai.tool." + chore.key());
                    return Result.failed(kind, new Issue("you have no " + chore.tool() + " to do it; a window has just "
                            + "opened for the player to lend you one, so ask them for it (you start as soon as you have it)",
                            AiLines.variant("work_no_tool", tool)));
                }
                if (work == Work.REFUSED) {
                    return Result.failed(kind, generic());
                }
            }
            case STOP_WORK -> {
                AiWork.stop(villager.getUUID(), true);
                AiErrands.stop(villager.getUUID());
                AiBuild.stop(villager.getUUID());
                McaHandles.runInteraction(villager, player, "stopworking");
            }
            case GUIDE, WAIT_AT, PICK_UP, STORE, FETCH, BREED -> {
                if (!AiErrands.start(villager, player, action, turn, villagerName, now)) {
                    return Result.failed(kind, new Issue("you do not know where that place is", AiLines.variant("cannot.generic")));
                }
            }
            // The player puts what to cook (and any fuel) in the cooking window; the errand starts when it closes.
            case COOK -> AiTasks.schedule(now + SCREEN_DELAY_TICKS, () -> {
                if (within(villager, player)) {
                    AiHandoverMenu.open(player, villager, "mcaconversations.ai.cook_title", 1, handed ->
                            AiErrands.startCook(villager, player, handed, villagerName, player.serverLevel().getGameTime()));
                }
            });
            case GIVE -> {
                if (!handOver(villager, player, villagerName, action.item(), action.amount(), now, false)) {
                    return Result.failed(kind, new Issue("you have nothing gathered to give right now",
                            AiLines.variant("give_nothing")));
                }
            }
            case BUILD -> openBuild(villager, player, action, List.of(), now);
            case DATE -> AiDates.agree(villager, player, villagerName, action, turn, now);
        }
        return Result.ok(kind);
    }

    private static Issue generic() {
        return new Issue("you could not do it right now", AiLines.variant("cannot.generic"));
    }

    /**
     * Whether a window can still open for this player a moment later: the villager alive and near, the
     * player still this living, connected player (a respawn makes a new player object).
     */
    private static boolean within(Entity villager, ServerPlayer player) {
        return villager.isAlive() && player.isAlive() && !player.isRemoved() && !player.hasDisconnected()
                && villager.distanceTo(player) <= 8;
    }

    enum Work { STARTED, NEEDS_TOOL, REFUSED }

    /**
     * Sends the villager to work. Without the tool for it, they say so and a window opens to lend them
     * one; the moment they have it, they get going.
     */
    static Work work(Entity villager, ServerPlayer player, String villagerName, AiChore chore, int amount, long now,
                     boolean speak) {
        if (AiWork.start(villager, player, chore, amount, villagerName, now)) {
            return Work.STARTED;
        }
        if (AiWork.hasTool(villager, chore)) {
            return Work.REFUSED; // MCA refused the chore itself
        }
        AiWork.awaitTool(villager, player, chore, amount, now);
        if (speak) {
            AiLines.sayLater(villager, player, AiLines.variant("work_no_tool",
                            Component.translatable("mcaconversations.ai.tool." + chore.key())), villagerName, now, 20,
                    AiEmotion.NEUTRAL, VoiceIntent.QUESTION);
        }
        // After the villager has asked for it (their line comes first, rewritten if need be).
        AiTasks.schedule(now + (speak ? SCREEN_DELAY_TICKS + 20 : 100), () -> {
            if (within(villager, player)) {
                AiHandoverMenu.open(player, villager, "mcaconversations.ai.tool_title", 1,
                        handed -> lend(villager, player, villagerName, handed));
            }
        });
        return Work.NEEDS_TOOL;
    }

    /**
     * Puts what the player hands over straight into the villager's own inventory (a loan, not a gift
     * MCA would consume), and starts the job they were waiting for if they now have the tool. Whatever
     * does not fit is given back. The handed stacks are left empty either way, so a caller never gives
     * them back a second time. Returns false when the villager has no inventory to take them.
     */
    static boolean lend(Entity villager, ServerPlayer player, String villagerName, List<ItemStack> handed) {
        Container inventory = McaHandles.inventory(villager);
        if (inventory == null) {
            handed.forEach(stack -> AiErrands.giveBack(player, villager, stack.split(stack.getCount())));
            return false;
        }
        boolean took = false;
        AiLivesSavedData lives = player.getServer() == null ? null : AiLivesSavedData.get(player.getServer());
        for (ItemStack stack : handed) {
            int before = stack.getCount();
            String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            ItemStack rest = AiErrands.insert(inventory, stack.copy());
            stack.setCount(rest.getCount()); // what went in is the villager's to hold now
            took |= rest.getCount() < before;
            if (lives != null && rest.getCount() < before) {
                // A loan: taking it back later is the player's right, not theft.
                lives.addLoan(villager.getUUID(), player.getUUID(), id, before - rest.getCount());
            }
            AiErrands.giveBack(player, villager, stack.split(stack.getCount()));
        }
        inventory.setChanged();
        long now = player.serverLevel().getGameTime();
        if (took) {
            AiConversations.markReceived(villager.getUUID(), player.getUUID(), now);
        }
        if (!AiWork.resumeIfReady(villager, player, villagerName, now)) {
            AiWork.awaiting(villager.getUUID(), now).ifPresentOrElse(chore -> AiLines.say(villager, player,
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
    static boolean handOver(Entity villager, ServerPlayer player, String villagerName, String item, int amount, long now,
                            boolean speak) {
        if ((item.isEmpty() || item.equals(AiIntent.ALL)) && AiWork.bringBack(villager, player, now)) {
            return true;
        }
        int given = item.isEmpty() || item.equals(AiIntent.ALL) ? giveGathered(villager, player)
                : give(villager, player, item, Math.max(1, amount == 0 ? 64 : amount));
        if (given == 0 && speak) {
            AiLines.sayLater(villager, player, AiLines.variant("give_nothing"), villagerName, now, 20,
                    AiEmotion.NEUTRAL, VoiceIntent.STATEMENT);
        }
        return given > 0;
    }

    /** Hands over everything the villager gathered (what any task brings in), keeping their tools. */
    static int giveGathered(Entity villager, ServerPlayer player) {
        Container inventory = McaHandles.inventory(villager);
        if (inventory == null) {
            return 0;
        }
        int given = 0;
        AiLivesSavedData lives = player.getServer() == null ? null : AiLivesSavedData.get(player.getServer());
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
            if (lives != null) {
                // Whatever of it this player had lent comes back to them: the loan is settled.
                lives.returnLoan(villager.getUUID(), player.getUUID(),
                        String.valueOf(BuiltInRegistries.ITEM.getKey(taken.getItem())), taken.getCount());
            }
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

    /**
     * Several villagers at once: each helper says it is coming, then everyone does the thing. Fails, as
     * the single-villager case would, when nobody at all could do it.
     */
    private static Result group(Entity leader, ServerPlayer player, String leaderName, AiEffect.Action action,
                                AiSocial.Turn turn, List<Entity> helpers, long now) {
        AiActionKind kind = action.kind();
        List<Entity> everyone = new java.util.ArrayList<>();
        everyone.add(leader);
        everyone.addAll(helpers);
        List<Entity> unable = new java.util.ArrayList<>();
        Result result = Result.ok(kind);
        switch (kind) {
            case WORK -> {
                AiChore chore = action.chore().orElse(null);
                if (chore == null) {
                    return Result.failed(kind, generic());
                }
                unable = AiWork.startGroup(everyone, player, chore, action.amount(), now);
                Component tool = Component.translatable("mcaconversations.ai.tool." + chore.key());
                if (unable.size() >= everyone.size()) {
                    // Nobody got going: the leader's line is rewritten to say so, and only helpers add theirs.
                    result = everyone.stream().anyMatch(e -> AiWork.hasTool(e, chore)) ? Result.failed(kind, generic())
                            : Result.failed(kind, new Issue("none of you has a " + chore.tool() + " to do it, so nobody "
                            + "could start", AiLines.variant("work_no_tool", tool)));
                }
                int n = 0;
                for (Entity e : unable) {
                    if (e == leader && !result.done()) {
                        continue;
                    }
                    AiLines.sayLater(e, player, AiWork.hasTool(e, chore) ? AiLines.variant("cannot.generic")
                                    : AiLines.variant("work_no_tool", tool), name(e), now,
                            25L * (++n), AiEmotion.NEUTRAL, VoiceIntent.STATEMENT);
                }
            }
            case PICK_UP, BREED -> {
                for (Entity e : everyone) {
                    if (!AiErrands.start(e, player, action, turn, name(e), e == leader, now)) {
                        unable.add(e);
                    }
                }
                if (unable.size() >= everyone.size()) {
                    result = Result.failed(kind, generic());
                }
            }
            case BUILD -> openBuild(leader, player, action, helpers, now);
            case FOLLOW, STAY, MOVE, GO_HOME -> {
                String command = switch (kind) {
                    case FOLLOW -> "FOLLOW";
                    case STAY -> "STAY";
                    case MOVE -> "MOVE";
                    default -> "gohome";
                };
                for (Entity e : everyone) {
                    if (kind != AiActionKind.STAY) {
                        VillagerAttention.release(e);
                    }
                    if (!McaHandles.runInteraction(e, player, command)) {
                        unable.add(e);
                    }
                }
                if (kind == AiActionKind.STAY && unable.size() >= everyone.size()) {
                    result = Result.failed(kind, generic()); // as alone, only staying put reports MCA's refusal
                }
            }
            default -> result = apply(leader, player, leaderName, new AiEffect.Action(kind, action.chore(),
                    action.amount(), action.item(), action.place()), turn, now);
        }
        if (!result.done()) {
            return result;
        }
        int i = 0;
        for (Entity helper : helpers) {
            if (!unable.contains(helper)) {
                AiLines.sayLater(helper, player, AiLines.variant("group_join"), name(helper), now, 20L * (++i),
                        AiEmotion.HAPPY, VoiceIntent.STATEMENT);
            }
        }
        return result;
    }

    /** The materials window; the build starts when it closes, with whoever was brought in to help. */
    private static void openBuild(Entity leader, ServerPlayer player, AiEffect.Action action, List<Entity> helpers, long now) {
        AiTasks.schedule(now + SCREEN_DELAY_TICKS, () -> {
            if (within(leader, player)) {
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
            if (within(villager, player)) {
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
        AiLivesSavedData lives = player.getServer() == null ? null : AiLivesSavedData.get(player.getServer());
        for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !AiPromises.matches(itemId, stack)) {
                continue;
            }
            ItemStack taken = stack.split(Math.min(left, stack.getCount()));
            left -= taken.getCount();
            if (lives != null) {
                // Handing back what this player lent settles the loan.
                lives.returnLoan(villager.getUUID(), player.getUUID(),
                        String.valueOf(BuiltInRegistries.ITEM.getKey(taken.getItem())), taken.getCount());
            }
            if (!player.getInventory().add(taken) && !taken.isEmpty()) {
                player.drop(taken, false);
            }
        }
        inventory.setChanged();
        int given = amount - left;
        if (given > 0) {
            // Without the tool they were working with, the job is over (the tool may have gone back to its owner).
            AiWork.job(villager.getUUID()).filter(j -> !AiWork.hasTool(villager, j.chore)).ifPresent(j -> {
                AiWork.stop(villager.getUUID(), true);
                McaHandles.runInteraction(villager, player, "stopworking");
            });
        }
        return given;
    }
}
