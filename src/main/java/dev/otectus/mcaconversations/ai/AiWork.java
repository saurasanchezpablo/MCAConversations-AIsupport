package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import dev.otectus.mcaconversations.chat.VillagerAttention;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.common.Tags;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Work a player sent a villager to do by word, and the player's view of it.
 *
 * <p>Chopping, harvesting, hunting and fishing are MCA's own chores: MCA's tasks do the work and keep
 * what they gather in the villager's inventory; this class assigns them through MCA's interaction
 * handler and watches. Mining is this mod's: MCA lists a "prospecting" chore but ships no task for
 * it, so the villager is put on that chore (which keeps MCA's brain out of the way) and this class
 * walks it to exposed natural stone and ore, digs with its pickaxe, and stores the drops.
 *
 * <p>The player sees a boss bar with the villager's name, the task and the count. With an amount
 * ("chop twenty logs") the villager stops when it has it, comes back to the player and hands it over.
 * Server thread only; jobs are not saved (MCA itself drops a chore when its player leaves).
 */
final class AiWork {

    /** How far around itself a miner looks for stone, and how far up or down. */
    static final int MINE_RADIUS = 10;
    static final int MINE_BELOW = 3;
    static final int MINE_ABOVE = 4;
    /** Ticks to reach a block before it is given up as unreachable. */
    static final long REACH_TIMEOUT_TICKS = 200;
    /** How long a villager has to come back with the goods before the job just ends. */
    static final long RETURN_TIMEOUT_TICKS = 1200;
    /** Without a target amount, the bar fills toward this many. */
    static final int OPEN_ENDED_SCALE = 64;

    enum Phase { WORKING, RETURNING }

    /** Several villagers on one task for one player: one shared bar, one total. */
    static final class Group {
        final ServerBossEvent bar;
        final AiChore chore;
        final int goal;
        final int size;

        Group(ServerBossEvent bar, AiChore chore, int goal, int size) {
            this.bar = bar;
            this.chore = chore;
            this.goal = goal;
            this.size = size;
        }
    }

    static final class Job {
        final UUID villager;
        final UUID player;
        final AiChore chore;
        final int goal;
        final int baseline;
        final ServerBossEvent bar;
        final Group group;
        Phase phase = Phase.WORKING;
        int mined;
        int lastGathered;
        long phaseSince;
        BlockPos target;
        long targetSince;
        int digTicks;
        int digNeeded;
        final Set<BlockPos> unreachable = new HashSet<>();

        Job(UUID villager, UUID player, AiChore chore, int goal, int baseline, ServerBossEvent bar, Group group, long now) {
            this.villager = villager;
            this.player = player;
            this.chore = chore;
            this.goal = goal;
            this.baseline = baseline;
            this.bar = bar;
            this.group = group;
            this.phaseSince = now;
        }
    }

    private static final Map<UUID, Job> JOBS = new HashMap<>();

    private AiWork() {
    }

    // --- what a chore needs and yields ------------------------------------------------------------

    static Predicate<ItemStack> tool(AiChore chore) {
        return switch (chore) {
            case CHOP -> s -> s.getItem() instanceof AxeItem;
            case HARVEST -> s -> s.getItem() instanceof HoeItem;
            case HUNT -> s -> s.getItem() instanceof SwordItem;
            case FISH -> s -> s.getItem() instanceof FishingRodItem;
            case MINE -> s -> s.getItem() instanceof PickaxeItem;
        };
    }

    static Predicate<ItemStack> yieldOf(AiChore chore) {
        return switch (chore) {
            case CHOP -> s -> s.is(ItemTags.LOGS);
            case HARVEST -> s -> s.is(Items.WHEAT) || s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT)
                    || s.is(Items.MELON_SLICE) || s.is(Items.PUMPKIN) || s.is(Items.SWEET_BERRIES);
            case HUNT -> s -> s.is(ItemTags.MEAT) || s.is(Items.LEATHER) || s.is(ItemTags.WOOL);
            case FISH -> s -> s.is(ItemTags.FISHES);
            case MINE -> s -> s.is(Tags.Items.COBBLESTONES) || s.is(Tags.Items.STONES) || s.is(Tags.Items.RAW_MATERIALS)
                    || s.is(Tags.Items.GEMS) || s.is(Items.COAL) || s.is(Items.REDSTONE) || s.is(Items.LAPIS_LAZULI)
                    || s.is(Items.FLINT);
        };
    }

    static boolean hasTool(Entity villager, AiChore chore) {
        Container inventory = McaHandles.inventory(villager);
        Predicate<ItemStack> tool = tool(chore);
        if (villager instanceof Mob mob && tool.test(mob.getMainHandItem())) {
            return true;
        }
        return inventory != null && slotOf(inventory, tool) >= 0;
    }

    static int count(Container inventory, Predicate<ItemStack> which) {
        int n = 0;
        if (inventory != null) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && which.test(stack)) {
                    n += stack.getCount();
                }
            }
        }
        return n;
    }

    private static int slotOf(Container inventory, Predicate<ItemStack> which) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).isEmpty() && which.test(inventory.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    // --- starting and stopping -------------------------------------------------------------------

    /** Sends the villager to work. Returns false (and assigns nothing) when it lacks the tool. */
    static boolean start(Entity villager, ServerPlayer player, AiChore chore, int goal, String villagerName, long now) {
        return start(villager, player, chore, goal, villagerName, null, now);
    }

    /**
     * Sends several villagers to work together for one player. The total is split between those who
     * have the tool; the others are returned so they can say why they cannot. One bar shows the total.
     */
    static List<Entity> startGroup(List<Entity> villagers, ServerPlayer player, AiChore chore, int total, long now) {
        List<Entity> able = villagers.stream().filter(v -> hasTool(v, chore)).toList();
        List<Entity> unable = villagers.stream().filter(v -> !hasTool(v, chore)).toList();
        if (able.isEmpty()) {
            return unable;
        }
        ServerBossEvent bar = new ServerBossEvent(groupTitle(able.size(), chore, 0, total), BossEvent.BossBarColor.GREEN,
                total > 0 ? BossEvent.BossBarOverlay.NOTCHED_10 : BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(0f);
        bar.addPlayer(player);
        Group group = new Group(bar, chore, total, able.size());
        for (int i = 0; i < able.size(); i++) {
            // An even share, the remainder to the first ones; 0 stays open-ended for everyone.
            int share = share(total, able.size(), i);
            Entity v = able.get(i);
            start(v, player, chore, share, McaCompat.getVillagerName(v).orElse(v.getName().getString()), group, now);
        }
        return unable;
    }

    /** One member's part of a group total: even shares, the remainder to the first; 0 stays open-ended. */
    static int share(int total, int size, int index) {
        return total <= 0 || size <= 0 ? 0 : total / size + (index < total % size ? 1 : 0);
    }

    private static boolean start(Entity villager, ServerPlayer player, AiChore chore, int goal, String villagerName,
                                 Group group, long now) {
        if (!hasTool(villager, chore)) {
            return false;
        }
        stop(villager.getUUID(), false);
        AiErrands.stop(villager.getUUID());
        VillagerAttention.release(villager);
        if (!McaHandles.runInteraction(villager, player, chore.mcaCommand())) {
            return false;
        }
        int baseline = chore == AiChore.MINE ? 0 : count(McaHandles.inventory(villager), yieldOf(chore));
        ServerBossEvent bar = group != null ? group.bar : new ServerBossEvent(title(villagerName, chore, 0, goal),
                BossEvent.BossBarColor.GREEN, goal > 0 ? BossEvent.BossBarOverlay.NOTCHED_10 : BossEvent.BossBarOverlay.PROGRESS);
        if (group == null) {
            bar.setProgress(0f);
            bar.addPlayer(player);
        }
        JOBS.put(villager.getUUID(), new Job(villager.getUUID(), player.getUUID(), chore, goal, baseline, bar, group, now));
        return true;
    }

    /** Ends the villager's job; with {@code toldToStop}, also tells MCA to drop the chore. */
    static void stop(UUID villager, boolean toldToStop) {
        Job job = JOBS.remove(villager);
        if (job != null) {
            release(job);
        }
    }

    /** Hides the job's bar, unless it is a group bar other members still use. */
    private static void release(Job job) {
        if (job.group == null || JOBS.values().stream().noneMatch(other -> other.group == job.group && other != job)) {
            job.bar.removeAllPlayers();
        }
    }

    private static Component groupTitle(int size, AiChore chore, int got, int goal) {
        return Component.translatable("mcaconversations.ai.group", size).append(" - ")
                .append(Component.translatable("mcaconversations.ai.chore." + chore.key()))
                .append(Component.literal(": " + got + (goal > 0 ? "/" + goal : "")));
    }

    static Optional<Job> job(UUID villager) {
        return Optional.ofNullable(JOBS.get(villager));
    }

    /** "Chopping: 12/20" for the prompt, so a villager asked how it is going answers truthfully. */
    static Optional<String> progressText(Entity villager) {
        Job job = JOBS.get(villager.getUUID());
        if (job == null) {
            return Optional.empty();
        }
        int got = gathered(job, villager);
        return Optional.of((job.phase == Phase.RETURNING ? "finished " : "") + job.chore.mcaCommand() + " "
                + job.chore.yield() + ": " + got + (job.goal > 0 ? " of " + job.goal : " so far"));
    }

    private static int gathered(Job job, Entity villager) {
        if (job.chore == AiChore.MINE) {
            return job.mined;
        }
        return Math.max(0, count(McaHandles.inventory(villager), yieldOf(job.chore)) - job.baseline);
    }

    private static Component title(String villagerName, AiChore chore, int got, int goal) {
        return Component.literal(villagerName + " - ")
                .append(Component.translatable("mcaconversations.ai.chore." + chore.key()))
                .append(Component.literal(": " + got + (goal > 0 ? "/" + goal : "")));
    }

    // --- every tick --------------------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        boolean progressTick = server.getTickCount() % 20 == 0;
        for (Iterator<Job> it = JOBS.values().iterator(); it.hasNext(); ) {
            Job job = it.next();
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(job.player);
                Entity villager = player == null ? null : player.serverLevel().getEntity(job.villager);
                if (player == null || villager == null || !villager.isAlive()) {
                    it.remove();
                    release(job);
                    continue;
                }
                if (job.phase == Phase.WORKING && job.chore == AiChore.MINE) {
                    mine(player.serverLevel(), villager, job, now);
                }
                if (progressTick && !progress(server, villager, player, job, now)) {
                    it.remove();
                    release(job);
                }
            } catch (Throwable t) {
                McaConversations.LOGGER.debug("AI work tick failed; dropping the job", t);
                it.remove();
                release(job);
            }
        }
    }

    /** Updates the bar and moves the job along. Returns false when the job is over. */
    private static boolean progress(MinecraftServer server, Entity villager, ServerPlayer player, Job job, long now) {
        String name = McaCompat.getVillagerName(villager).orElse(villager.getName().getString());
        int got = gathered(job, villager);
        job.lastGathered = got;
        if (job.group != null) {
            int total = JOBS.values().stream().filter(j -> j.group == job.group).mapToInt(j -> j.lastGathered).sum();
            job.bar.setName(groupTitle(job.group.size, job.chore, total, job.group.goal));
            job.bar.setProgress(Math.min(1f, total / (float) (job.group.goal > 0 ? job.group.goal : OPEN_ENDED_SCALE)));
        } else {
            job.bar.setName(title(name, job.chore, got, job.goal));
            job.bar.setProgress(Math.min(1f, got / (float) (job.goal > 0 ? job.goal : OPEN_ENDED_SCALE)));
        }
        if (job.phase == Phase.WORKING) {
            String current = McaCompat.getCurrentChore(villager).orElse("NONE").toUpperCase(java.util.Locale.ROOT);
            if (!current.equals(job.chore.mcaChoreName())) {
                return false; // MCA dropped the chore (no tool left, told to stop, player away)
            }
            boolean full = inventoryFull(McaHandles.inventory(villager));
            if ((job.goal > 0 && got >= job.goal) || full) {
                McaHandles.runInteraction(villager, player, "stopworking");
                McaHandles.runInteraction(villager, player, "FOLLOW");
                job.phase = Phase.RETURNING;
                job.phaseSince = now;
                if (job.group == null) {
                    job.bar.setColor(BossEvent.BossBarColor.BLUE);
                }
            }
            return true;
        }
        // Returning: hand the goods over once close enough, or give up after a while.
        if (villager.distanceTo(player) <= 3.5) {
            int handed = handOver(villager, player, job, got);
            McaHandles.runInteraction(villager, player, "MOVE");
            AiLines.say(villager, player, AiLines.variant("work_done", handed,
                    Component.translatable("mcaconversations.ai.yield." + job.chore.key())), name, AiEmotion.PROUD,
                    dev.otectus.mcaconversations.voice.VoiceIntent.STATEMENT);
            return false;
        }
        if (now - job.phaseSince > RETURN_TIMEOUT_TICKS) {
            McaHandles.runInteraction(villager, player, "MOVE");
            return false;
        }
        return true;
    }

    /** Moves up to {@code amount} gathered items from the villager to the player (overflow drops at their feet). */
    private static int handOver(Entity villager, ServerPlayer player, Job job, int amount) {
        Container inventory = McaHandles.inventory(villager);
        if (inventory == null || amount <= 0) {
            return 0;
        }
        Predicate<ItemStack> which = yieldOf(job.chore);
        int left = amount;
        for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !which.test(stack)) {
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

    private static boolean inventoryFull(Container inventory) {
        if (inventory == null) {
            return false;
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    // --- this mod's mining ------------------------------------------------------------------------

    static boolean minable(ServerLevel level, BlockPos pos, BlockState state, ItemStack tool) {
        if (!(state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Tags.Blocks.ORES) || state.is(Tags.Blocks.COBBLESTONES))) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0 || hardness > 50 || !tool.isCorrectToolForDrops(state)) {
            return false;
        }
        for (Direction d : Direction.values()) {
            if (level.getBlockState(pos.relative(d)).isAir()) {
                return true; // exposed: the villager can reach it without tunnelling
            }
        }
        return false;
    }

    private static void mine(ServerLevel level, Entity entity, Job job, long now) {
        if (!(entity instanceof Mob villager)) {
            return;
        }
        ItemStack tool = villager.getMainHandItem();
        if (!(tool.getItem() instanceof PickaxeItem)) {
            Container inventory = McaHandles.inventory(villager);
            int slot = inventory == null ? -1 : slotOf(inventory, tool(AiChore.MINE));
            if (slot < 0) {
                return; // progress() sees MCA still on the chore; the bar simply stalls until given a pickaxe
            }
            villager.setItemInHand(InteractionHand.MAIN_HAND, inventory.getItem(slot));
            tool = villager.getMainHandItem();
        }
        if (job.target != null && (!minable(level, job.target, level.getBlockState(job.target), tool)
                || now - job.targetSince > REACH_TIMEOUT_TICKS && job.digTicks == 0)) {
            if (job.digTicks == 0) {
                job.unreachable.add(job.target);
            }
            level.destroyBlockProgress(villager.getId(), job.target, -1);
            job.target = null;
        }
        if (job.target == null) {
            if (now % 20 != 0) {
                return;
            }
            job.target = findStone(level, villager.blockPosition(), tool, job.unreachable);
            job.targetSince = now;
            job.digTicks = 0;
            if (job.target == null) {
                return;
            }
            BlockState state = level.getBlockState(job.target);
            job.digNeeded = Math.max(10, (int) Math.ceil(state.getDestroySpeed(level, job.target) * 30f
                    / Math.max(1f, tool.getDestroySpeed(state))));
        }
        BlockPos target = job.target;
        double distance = villager.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (distance > 2.8 * 2.8) {
            if (now % 10 == 0) {
                villager.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
            }
            return;
        }
        villager.getNavigation().stop();
        villager.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (job.digTicks % 4 == 0) {
            villager.swing(InteractionHand.MAIN_HAND);
        }
        job.digTicks++;
        level.destroyBlockProgress(villager.getId(), target, Math.min(9, job.digTicks * 10 / job.digNeeded));
        if (job.digTicks < job.digNeeded) {
            return;
        }
        BlockState state = level.getBlockState(target);
        Container inventory = McaHandles.inventory(villager);
        for (ItemStack drop : Block.getDrops(state, level, target, level.getBlockEntity(target), villager, tool)) {
            ItemStack rest = inventory instanceof net.minecraft.world.SimpleContainer simple ? simple.addItem(drop) : drop;
            if (!rest.isEmpty()) {
                level.addFreshEntity(new ItemEntity(level, villager.getX(), villager.getY() + 0.5, villager.getZ(), rest));
            }
        }
        level.destroyBlockProgress(villager.getId(), target, -1);
        level.destroyBlock(target, false, villager);
        tool.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
        job.mined++;
        job.target = null;
        job.digTicks = 0;
    }

    /** The nearest exposed natural stone or ore around the villager, not dug from below its feet's reach. */
    private static BlockPos findStone(ServerLevel level, BlockPos from, ItemStack tool, Set<BlockPos> skip) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -MINE_RADIUS; dx <= MINE_RADIUS; dx++) {
            for (int dz = -MINE_RADIUS; dz <= MINE_RADIUS; dz++) {
                for (int dy = -MINE_BELOW; dy <= MINE_ABOVE; dy++) {
                    pos.set(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
                    double d = from.distSqr(pos);
                    if (d >= bestDistance || skip.contains(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (minable(level, pos, state, tool)) {
                        best = pos.immutable();
                        bestDistance = d;
                    }
                }
            }
        }
        return best;
    }

    static void forgetPlayer(UUID player) {
        List<Job> gone = JOBS.values().stream().filter(job -> job.player.equals(player)).toList();
        gone.forEach(job -> JOBS.remove(job.villager));
        gone.forEach(job -> job.bar.removeAllPlayers());
    }

    static void reset() {
        JOBS.values().forEach(job -> job.bar.removeAllPlayers());
        JOBS.clear();
    }
}
