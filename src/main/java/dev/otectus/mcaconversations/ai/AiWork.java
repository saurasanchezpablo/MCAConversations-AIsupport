package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
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
        /** Both set again once it is known who actually got going. */
        int goal;
        int size;

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
        /** Digging a staircase down to stone when none is exposed nearby. */
        BlockPos stairStart;
        Direction stairDir;
        int stairStep = 1;
        BlockPos stairStand;
        boolean stairTarget;
        /** The staircase's steps: never mined, or the villager could not climb back out. */
        final Set<BlockPos> steps = new HashSet<>();
        /** Nothing left to mine within reach: the villager says so and comes back. */
        boolean gaveUp;

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

    /** Whether this stack is the tool for any task (an axe, hoe, sword, fishing rod or pickaxe). */
    static boolean isTool(ItemStack stack) {
        for (AiChore chore : AiChore.values()) {
            if (tool(chore).test(stack)) {
                return true;
            }
        }
        return false;
    }

    /** The task an item is the tool for ({@code minecraft:iron_axe} is for chopping), if it is a tool. */
    static java.util.Optional<AiChore> toolFor(String itemId) {
        if (itemId == null || itemId.isEmpty() || itemId.startsWith("#") || !AiPromises.itemExists(itemId)) {
            return java.util.Optional.empty();
        }
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        ItemStack stack = id == null ? ItemStack.EMPTY
                : new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id));
        for (AiChore chore : AiChore.values()) {
            if (!stack.isEmpty() && tool(chore).test(stack)) {
                return java.util.Optional.of(chore);
            }
        }
        return java.util.Optional.empty();
    }

    /** Whether the villager has this item (or an item of this {@code #tag}) in hand or in its inventory. */
    static boolean holds(Entity villager, String itemRef) {
        if (villager instanceof Mob mob && AiPromises.matches(itemRef, mob.getMainHandItem())) {
            return true;
        }
        Container inventory = McaHandles.inventory(villager);
        return inventory != null && slotOf(inventory, s -> AiPromises.matches(itemRef, s)) >= 0;
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
     * have the tool; the others, and any MCA would not put on the chore, are returned so they can say
     * why they cannot. One bar shows the total, once anyone has started.
     */
    static List<Entity> startGroup(List<Entity> villagers, ServerPlayer player, AiChore chore, int total, long now) {
        List<Entity> able = villagers.stream().filter(v -> hasTool(v, chore)).toList();
        List<Entity> unable = new java.util.ArrayList<>(villagers.stream().filter(v -> !hasTool(v, chore)).toList());
        if (able.isEmpty()) {
            return unable;
        }
        ServerBossEvent bar = new ServerBossEvent(groupTitle(able.size(), chore, 0, total), BossEvent.BossBarColor.GREEN,
                total > 0 ? BossEvent.BossBarOverlay.NOTCHED_10 : BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(0f);
        Group group = new Group(bar, chore, total, able.size());
        int started = 0;
        int goal = 0;
        for (int i = 0; i < able.size(); i++) {
            // An even share, the remainder to the first ones; 0 stays open-ended for everyone.
            int share = share(total, able.size(), i);
            Entity v = able.get(i);
            if (start(v, player, chore, share, McaCompat.getVillagerName(v).orElse(v.getName().getString()), group, now)) {
                started++;
                goal += share;
            } else {
                unable.add(v);
            }
        }
        if (started > 0) {
            // The bar counts only those who got going, toward the shares they took on.
            group.size = started;
            group.goal = goal;
            bar.setName(groupTitle(started, chore, 0, goal));
            bar.addPlayer(player);
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

    /** Work asked for while the villager had no tool: it starts as soon as they are handed one. */
    record Pending(UUID player, AiChore chore, int amount, long until) {
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    /** How long a villager waits for a tool before forgetting the request. */
    static final long PENDING_TICKS = 6_000;

    static void awaitTool(Entity villager, ServerPlayer player, AiChore chore, int amount, long now) {
        PENDING.put(villager.getUUID(), new Pending(player.getUUID(), chore, amount, now + PENDING_TICKS));
    }

    /**
     * The villager was just handed something: if they were waiting for a tool to do a job and now have
     * it, they get going. Returns whether the job started.
     */
    static boolean resumeIfReady(Entity villager, ServerPlayer player, String villagerName, long now) {
        Pending pending = PENDING.get(villager.getUUID());
        if (pending == null) {
            return false;
        }
        if (now > pending.until()) {
            PENDING.remove(villager.getUUID());
            return false;
        }
        // Someone else handing something over leaves the request standing for the one who made it.
        if (!pending.player().equals(player.getUUID()) || !hasTool(villager, pending.chore())) {
            return false;
        }
        PENDING.remove(villager.getUUID());
        return start(villager, player, pending.chore(), pending.amount(), villagerName, now);
    }

    /** The task the villager is still waiting for a tool for, if the request has not lapsed by {@code now}. */
    static Optional<AiChore> awaiting(UUID villager, long now) {
        Pending pending = PENDING.get(villager);
        if (pending != null && now > pending.until()) {
            PENDING.remove(villager);
            return Optional.empty();
        }
        return Optional.ofNullable(pending).map(Pending::chore);
    }

    /**
     * "Bring me what you have": a villager working for this player stops, comes back and hands it over,
     * and with them everyone else on the same group job. False when they are not working for the player.
     */
    static boolean bringBack(Entity villager, ServerPlayer player, long now) {
        Job job = JOBS.get(villager.getUUID());
        if (job == null || !job.player.equals(player.getUUID())) {
            return false;
        }
        for (Job j : JOBS.values()) {
            if ((j == job || (job.group != null && j.group == job.group)) && j.phase == Phase.WORKING) {
                Entity worker = player.serverLevel().getEntity(j.villager);
                if (worker != null) {
                    McaHandles.runInteraction(worker, player, "stopworking");
                    McaHandles.runInteraction(worker, player, "FOLLOW");
                }
                j.phase = Phase.RETURNING;
                j.phaseSince = now;
            }
        }
        return true;
    }

    /** Ends the villager's job; with {@code toldToStop}, also tells MCA to drop the chore. */
    static void stop(UUID villager, boolean toldToStop) {
        if (toldToStop) {
            PENDING.remove(villager);
        }
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
        if (!PENDING.isEmpty() && server.getTickCount() % 5 == 0) {
            prunePending(server, server.overworld().getGameTime());
        }
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
                    // Without a player there is no one to send MCA's "stopworking" as (it needs one); a miner
                    // then stays on the prospecting chore, which has no task, until given another order.
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
            boolean reachedGoal = job.goal > 0 && got >= job.goal;
            if (!current.equals(job.chore.mcaChoreName()) || job.gaveUp && !reachedGoal) {
                if (McaConversationsConfig.debugAi()) {
                    McaConversations.LOGGER.info("[ai] work {} ended early: chore={} gaveUp={} got={} at {}", job.chore,
                            current, job.gaveUp, got, villager.blockPosition());
                }
                // MCA dropped the chore (the tool broke, nothing left to work nearby). Say so, and bring back
                // whatever was gathered rather than leaving the player wondering.
                AiLines.say(villager, player, AiLines.variant("work_gave_up",
                                Component.translatable("mcaconversations.ai.chore." + job.chore.key())), name,
                        AiEmotion.SAD, dev.otectus.mcaconversations.voice.VoiceIntent.STATEMENT);
                McaHandles.runInteraction(villager, player, "stopworking");
                if (got <= 0) {
                    return false;
                }
                McaHandles.runInteraction(villager, player, "FOLLOW");
                job.phase = Phase.RETURNING;
                job.phaseSince = now;
                return true;
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
            if (player.getServer() != null) {
                AiSkills.practice(player.getServer(), villager, job.chore, handed); // practice makes them better at it
            }
            McaHandles.runInteraction(villager, player, "MOVE");
            AiLines.say(villager, player, AiLines.variant("work_done", handed,
                    Component.translatable("mcaconversations.ai.yield." + job.chore.key())), name, AiEmotion.PROUD,
                    dev.otectus.mcaconversations.voice.VoiceIntent.STATEMENT);
            return false;
        }
        if (now - job.phaseSince > RETURN_TIMEOUT_TICKS) {
            if (McaConversationsConfig.debugAi()) {
                McaConversations.LOGGER.info("[ai] work {} could not get back to the player: villager at {}, player at {}",
                        job.chore, villager.blockPosition(), player.blockPosition());
            }
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

    static final int MAX_STAIR_STEPS = 40;

    /** Natural ground a villager may dig through: soil, sand, gravel, clay, stone and ores. Never buildings or liquids. */
    static boolean diggable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state == null || state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty()) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0 || hardness > 50) {
            return false;
        }
        return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Tags.Blocks.ORES) || state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND) || state.is(Tags.Blocks.GRAVELS) || state.is(net.minecraft.world.level.block.Blocks.CLAY)
                || state.is(Tags.Blocks.COBBLESTONES) || state.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)
                || state.is(net.minecraft.world.level.block.Blocks.FARMLAND);
    }

    private static boolean nearLiquid(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (!level.getFluidState(pos.relative(d)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The blocks one stair step down needs cleared: room for head and feet, and for the step down. */
    private static BlockPos[] stepBlocks(BlockPos feet) {
        return new BlockPos[]{feet.above(2), feet.above(), feet};
    }

    private static boolean stairWay(ServerLevel level, BlockPos start, Direction dir) {
        for (int step = 1; step <= 3; step++) {
            BlockPos feet = start.relative(dir, step).below(step);
            for (BlockPos b : stepBlocks(feet)) {
                BlockState st = level.getBlockState(b);
                if (!(st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty() || diggable(level, b, st))
                        || nearLiquid(level, b)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The next block to dig for a staircase going down from where the villager started, or null when the
     * way is blocked (a building, water, lava, a cave) or deep enough. Loose plants in the way are cleared.
     */
    private static BlockPos nextStair(ServerLevel level, Mob villager, Job job) {
        if (job.stairStart == null) {
            job.stairStart = villager.blockPosition();
            job.steps.add(job.stairStart.below());
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (stairWay(level, job.stairStart, dir)) {
                    job.stairDir = dir;
                    break;
                }
            }
        }
        if (job.stairDir == null) {
            return null;
        }
        while (job.stairStep <= MAX_STAIR_STEPS) {
            BlockPos feet = job.stairStart.relative(job.stairDir, job.stairStep).below(job.stairStep);
            if (feet.getY() < level.getMinBuildHeight() + 6) {
                return null;
            }
            for (BlockPos b : stepBlocks(feet)) {
                BlockState st = level.getBlockState(b);
                if (st.isAir()) {
                    continue;
                }
                if (st.canBeReplaced() && st.getFluidState().isEmpty()) {
                    level.destroyBlock(b, false, villager); // a tuft of grass or a flower
                    continue;
                }
                if (!diggable(level, b, st) || nearLiquid(level, b)) {
                    return null;
                }
                job.stairStand = job.stairStep == 1 ? job.stairStart
                        : job.stairStart.relative(job.stairDir, job.stairStep - 1).below(job.stairStep - 1);
                return b;
            }
            if (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) {
                return null; // a cave or a drop below: not safe to go on
            }
            job.steps.add(feet.below().immutable());
            job.stairStep++;
        }
        return null;
    }

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
        Container toolInventory = null;
        if (!(tool.getItem() instanceof PickaxeItem)) {
            // The pickaxe is used where it lies in the bag, never moved: putting the same stack in the hand
            // would share it between two slots (and duplicate it on save) and drop what the hand held.
            toolInventory = McaHandles.inventory(villager);
            int slot = toolInventory == null ? -1 : slotOf(toolInventory, tool(AiChore.MINE));
            if (slot < 0) {
                return; // progress() sees MCA still on the chore; the bar simply stalls until given a pickaxe
            }
            tool = toolInventory.getItem(slot);
        }
        BlockState current = job.target == null ? null : level.getBlockState(job.target);
        boolean stillThere = job.target != null && (job.stairTarget ? diggable(level, job.target, current)
                : minable(level, job.target, current, tool));
        if (job.target != null && (!stillThere || now - job.targetSince > REACH_TIMEOUT_TICKS && job.digTicks == 0)) {
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
            Set<BlockPos> skip = new HashSet<>(job.unreachable);
            skip.addAll(job.steps);
            skip.add(villager.blockPosition().below()); // never the ground under their own feet
            // Once digging down, only the staircase itself is dug: loose stone in its walls would break
            // the way back up.
            job.target = job.stairStart == null ? findStone(level, villager.blockPosition(), tool, skip) : null;
            job.stairTarget = false;
            if (job.target == null) {
                // No stone in sight (it is under the grass): dig a staircase down to it, as a player would.
                job.target = nextStair(level, villager, job);
                job.stairTarget = job.target != null;
                if (job.target == null) {
                    job.gaveUp = true;
                    return;
                }
            }
            job.targetSince = now;
            job.digTicks = 0;
            BlockState state = level.getBlockState(job.target);
            // A practised miner digs faster.
            double skill = AiSkills.speed(AiSkills.level(level.getServer(), entity.getUUID(), AiChore.MINE));
            job.digNeeded = Math.max(6, (int) Math.ceil(state.getDestroySpeed(level, job.target) * 30f
                    / Math.max(1f, tool.getDestroySpeed(state)) * skill));
        }
        BlockPos target = job.target;
        double distance = villager.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (distance > 2.8 * 2.8) {
            if (now % 10 == 0) {
                BlockPos goal = job.stairTarget && job.stairStand != null ? job.stairStand : target;
                villager.getNavigation().moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 0.6);
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
        int yielded = 0;
        // One roll of the loot table: what is counted is exactly what is stored.
        for (ItemStack drop : Block.getDrops(state, level, target, level.getBlockEntity(target), villager, tool)) {
            if (yieldOf(AiChore.MINE).test(drop)) {
                yielded += drop.getCount();
            }
            ItemStack rest = inventory == null ? drop : AiErrands.insert(inventory, drop);
            if (!rest.isEmpty()) {
                level.addFreshEntity(new ItemEntity(level, villager.getX(), villager.getY() + 0.5, villager.getZ(), rest));
            }
        }
        if (inventory != null) {
            inventory.setChanged();
        }
        level.destroyBlockProgress(villager.getId(), target, -1);
        level.destroyBlock(target, false, villager);
        String toolId = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(tool.getItem()));
        tool.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
        if (toolInventory != null) {
            toolInventory.setChanged();
        }
        if (tool.isEmpty() && level.getServer() != null) {
            // A lent pickaxe worn out on the job is gone for good: nobody can take it back now.
            AiLivesSavedData.get(level.getServer()).writeOffLoan(villager.getUUID(), job.player, toolId, 1);
        }
        job.mined += yielded; // dirt dug on the way down is not what was asked for
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

    /**
     * As {@link #forgetPlayer(UUID)}, while the player is still here to send MCA's command as: a miner is
     * taken off the prospecting chore, which no MCA task would ever end.
     */
    static void forgetPlayer(ServerPlayer player) {
        for (Job job : JOBS.values()) {
            if (job.player.equals(player.getUUID()) && job.chore == AiChore.MINE && job.phase == Phase.WORKING) {
                Entity villager = AiErrands.find(player.getServer(), job.villager);
                if (villager != null && villager.isAlive()) {
                    McaHandles.runInteraction(villager, player, "stopworking");
                }
            }
        }
        forgetPlayer(player.getUUID());
    }

    /** Drops tool requests that lapsed, or whose villager has died. */
    private static void prunePending(MinecraftServer server, long now) {
        PENDING.entrySet().removeIf(e -> {
            if (now > e.getValue().until()) {
                return true;
            }
            Entity villager = AiErrands.find(server, e.getKey());
            return villager != null && !villager.isAlive();
        });
    }

    static void reset() {
        JOBS.values().forEach(job -> job.bar.removeAllPlayers());
        JOBS.clear();
        PENDING.clear();
    }
}
