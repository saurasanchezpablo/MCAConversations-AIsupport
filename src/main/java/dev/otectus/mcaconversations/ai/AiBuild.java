package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.chat.VillagerAttention;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import dev.otectus.mcaconversations.voice.VoiceIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Building something simple together, by word: "build me a hut here", "make a pen for the sheep",
 * "let's put a campfire here". The player hands over the materials in a window. The villager, and any
 * neighbours brought in to help, then lay it out in front of where the player stood, block by block,
 * walking to each spot, swinging and placing with the block's own sound.
 *
 * <p>Only from what was handed over, and only into air or plants: nothing is ever destroyed, apart
 * from grass turned into a path or tilled into a field. What is left over comes back. A boss bar
 * shows the progress.
 */
final class AiBuild {

    /** What can be built. */
    static final Set<String> TEMPLATES = Set.of("hut", "pen", "campfire", "plot", "path", "wall");

    enum Role { WALL, ROOF, DOOR, FENCE, GATE, CAMPFIRE, SEAT, PATH, FARMLAND, WATER, CROP, TORCH }

    /** One block of a plan, in local coordinates: x to the right, y up, z away from the player. */
    record Piece(int x, int y, int z, Role role) {
    }

    static final int PLACE_TICKS = 8;
    static final long REACH_TICKS = 200;
    static final long TIMEOUT_TICKS = 12_000;
    static final double REACH = 3.5;

    private static final class Build {
        final UUID player;
        final UUID leader;
        final List<UUID> workers;
        final String template;
        final BlockPos origin;
        final Direction facing;
        final List<Piece> queue;
        final List<ItemStack> stock;
        final ServerBossEvent bar;
        final long started;
        final int total;
        final Map<UUID, Piece> claimed = new HashMap<>();
        final Map<UUID, Long> claimedAt = new HashMap<>();
        final Map<UUID, Integer> working = new HashMap<>();
        final Set<Role> missing = EnumSet.noneOf(Role.class);
        int placed;

        Build(UUID player, UUID leader, List<UUID> workers, String template, BlockPos origin, Direction facing,
              List<Piece> queue, List<ItemStack> stock, ServerBossEvent bar, long now) {
            this.player = player;
            this.leader = leader;
            this.workers = workers;
            this.template = template;
            this.origin = origin;
            this.facing = facing;
            this.queue = new ArrayList<>(queue);
            this.stock = stock;
            this.bar = bar;
            this.started = now;
            this.total = queue.size();
        }
    }

    private static final List<Build> BUILDS = new ArrayList<>();

    private AiBuild() {
    }

    static boolean busy(UUID villager) {
        return BUILDS.stream().anyMatch(b -> b.workers.contains(villager));
    }

    // --- plans ----------------------------------------------------------------------------------------

    /** The pieces of a template, in building order: ground work, then bottom-up. Pure. */
    static List<Piece> layout(String template) {
        List<Piece> out = new ArrayList<>();
        switch (template) {
            case "hut" -> {
                for (int y = 0; y <= 2; y++) {
                    for (int x = -2; x <= 2; x++) {
                        for (int z = 1; z <= 5; z++) {
                            boolean edge = x == -2 || x == 2 || z == 1 || z == 5;
                            boolean doorway = x == 0 && z == 1 && y <= 1;
                            if (edge && !doorway) {
                                out.add(new Piece(x, y, z, Role.WALL));
                            }
                        }
                    }
                }
                out.add(new Piece(0, 0, 1, Role.DOOR));
                for (int x = -2; x <= 2; x++) {
                    for (int z = 1; z <= 5; z++) {
                        out.add(new Piece(x, 3, z, Role.ROOF));
                    }
                }
                out.add(new Piece(0, 0, 4, Role.TORCH));
            }
            case "pen" -> {
                for (int x = -3; x <= 3; x++) {
                    for (int z = 1; z <= 7; z++) {
                        if (x == -3 || x == 3 || z == 1 || z == 7) {
                            out.add(new Piece(x, 0, z, x == 0 && z == 1 ? Role.GATE : Role.FENCE));
                        }
                    }
                }
            }
            case "campfire" -> {
                out.add(new Piece(0, 0, 3, Role.CAMPFIRE));
                out.add(new Piece(-2, 0, 3, Role.SEAT));
                out.add(new Piece(2, 0, 3, Role.SEAT));
                out.add(new Piece(0, 0, 5, Role.SEAT));
            }
            case "plot" -> {
                for (int x = -2; x <= 2; x++) {
                    for (int z = 1; z <= 5; z++) {
                        out.add(new Piece(x, -1, z, x == 0 && z == 3 ? Role.WATER : Role.FARMLAND));
                    }
                }
                for (int x = -2; x <= 2; x++) {
                    for (int z = 1; z <= 5; z++) {
                        if (!(x == 0 && z == 3)) {
                            out.add(new Piece(x, 0, z, Role.CROP));
                        }
                    }
                }
            }
            case "path" -> {
                for (int z = 1; z <= 10; z++) {
                    out.add(new Piece(0, -1, z, Role.PATH));
                }
                for (int z = 2; z <= 10; z += 4) {
                    out.add(new Piece(1, 0, z, Role.TORCH));
                }
            }
            case "wall" -> {
                for (int y = 0; y <= 1; y++) {
                    for (int x = -3; x <= 3; x++) {
                        out.add(new Piece(x, y, 2, Role.WALL));
                    }
                }
            }
            default -> {
            }
        }
        return out;
    }

    /** Where a local piece lands in the world. Pure. */
    static BlockPos place(BlockPos origin, Direction facing, Piece piece) {
        Direction right = facing.getClockWise();
        return origin.offset(right.getStepX() * piece.x() + facing.getStepX() * piece.z(), piece.y(),
                right.getStepZ() * piece.x() + facing.getStepZ() * piece.z());
    }

    // --- materials --------------------------------------------------------------------------------------

    /** Whether this stack can be used for this role. */
    static boolean fits(Role role, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Block block = stack.getItem() instanceof BlockItem item ? item.getBlock() : null;
        return switch (role) {
            case WALL -> block != null && solid(block);
            case ROOF -> block != null && (block instanceof SlabBlock || block instanceof StairBlock || solid(block));
            case DOOR -> block instanceof DoorBlock;
            case FENCE -> block instanceof FenceBlock || block instanceof WallBlock;
            case GATE -> block instanceof FenceGateBlock;
            case CAMPFIRE -> block instanceof CampfireBlock;
            case SEAT -> stack.is(ItemTags.LOGS) || (block != null && solid(block));
            case TORCH -> block instanceof TorchBlock;
            case CROP -> block instanceof CropBlock;
            case WATER -> stack.is(Items.WATER_BUCKET);
            case PATH, FARMLAND -> true; // ground work needs no material
        };
    }

    private static boolean solid(Block block) {
        BlockState state = block.defaultBlockState();
        return !(block instanceof EntityBlock) && state.isSolid() && !state.hasBlockEntity()
                && !(block instanceof DoorBlock) && !(block instanceof FenceBlock) && !(block instanceof SlabBlock)
                && !(block instanceof StairBlock) && !(block instanceof net.minecraft.world.level.block.FallingBlock);
    }

    /** The stack to use for a role: a roof prefers slabs, a wall the most plentiful block. */
    private static Optional<ItemStack> material(List<ItemStack> stock, Role role) {
        ItemStack best = null;
        for (ItemStack stack : stock) {
            if (!fits(role, stack)) {
                continue;
            }
            boolean preferred = role == Role.ROOF && stack.getItem() instanceof BlockItem b && b.getBlock() instanceof SlabBlock;
            boolean bestPreferred = best != null && role == Role.ROOF && best.getItem() instanceof BlockItem bb
                    && bb.getBlock() instanceof SlabBlock;
            if (best == null || (preferred && !bestPreferred) || (preferred == bestPreferred && stack.getCount() > best.getCount())) {
                best = stack;
            }
        }
        return Optional.ofNullable(best);
    }

    // --- starting ----------------------------------------------------------------------------------------

    /**
     * Starts the build in front of where the player stands, facing the way they face. False (the caller
     * returns the materials) when there is no room, or a field is asked for without a hoe.
     */
    static boolean start(Entity leader, List<Entity> helpers, ServerPlayer player, String template, List<ItemStack> stock,
                         long now) {
        ServerLevel level = player.serverLevel();
        String leaderName = name(leader);
        Direction facing = player.getDirection();
        BlockPos origin = player.blockPosition();
        List<Piece> pieces = layout(template);
        if (pieces.isEmpty()) {
            return false;
        }
        if (template.equals("plot") && stock.stream().noneMatch(s -> s.getItem() instanceof HoeItem) && !hasHoe(leader)) {
            say(leader, player, "build_no_hoe", leaderName);
            return false;
        }
        int blocked = 0;
        for (Piece piece : pieces) {
            if (!free(level, place(origin, facing, piece), piece.role(), false)) {
                blocked++;
            }
        }
        if (blocked * 10 > pieces.size() * 4) {
            say(leader, player, "build_no_room", leaderName);
            return false;
        }
        List<UUID> workers = new ArrayList<>();
        List<Entity> team = new ArrayList<>();
        team.add(leader);
        team.addAll(helpers);
        for (Entity worker : team) {
            AiErrands.stop(worker.getUUID());
            AiWork.stop(worker.getUUID(), true);
            VillagerAttention.release(worker);
            McaHandles.runInteraction(worker, player, AiChore.MINE.mcaCommand()); // MCA's brain stands aside
            workers.add(worker.getUUID());
        }
        ServerBossEvent bar = new ServerBossEvent(title(leaderName, template, workers.size(), 0, pieces.size()),
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_10);
        bar.addPlayer(player);
        BUILDS.add(new Build(player.getUUID(), leader.getUUID(), workers, template, origin, facing, pieces,
                new ArrayList<>(stock), bar, now));
        say(leader, player, "build_start", leaderName);
        return true;
    }

    private static boolean hasHoe(Entity villager) {
        Container inventory = McaHandles.inventory(villager);
        if (inventory == null) {
            return false;
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).getItem() instanceof HoeItem) {
                return true;
            }
        }
        return false;
    }

    private static Component title(String name, String template, int workers, int placed, int total) {
        return Component.literal(name + (workers > 1 ? " +" + (workers - 1) : "") + " - ")
                .append(Component.translatable("mcaconversations.ai.build." + template))
                .append(Component.literal(": " + placed + "/" + total));
    }

    // --- every tick ----------------------------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        if (BUILDS.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (Iterator<Build> it = BUILDS.iterator(); it.hasNext(); ) {
            Build build = it.next();
            boolean keep;
            try {
                keep = step(server, build, now);
            } catch (Throwable t) {
                McaConversations.LOGGER.debug("AI build failed; ending it", t);
                keep = false;
            }
            if (!keep) {
                finish(server, build);
                it.remove();
            }
        }
    }

    private static boolean step(MinecraftServer server, Build build, long now) {
        ServerPlayer player = server.getPlayerList().getPlayer(build.player);
        if (player == null || now - build.started > TIMEOUT_TICKS) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        boolean anyone = false;
        for (UUID id : build.workers) {
            Entity entity = level.getEntity(id);
            if (!(entity instanceof Mob worker) || !worker.isAlive()) {
                build.claimed.remove(id);
                continue;
            }
            anyone = true;
            work(level, worker, build, now);
        }
        if (now % 20 == 0) {
            String leaderName = Optional.ofNullable(level.getEntity(build.leader)).map(AiBuild::name).orElse("");
            build.bar.setName(title(leaderName, build.template, build.workers.size(), build.placed, build.total));
            build.bar.setProgress(Math.min(1f, (build.total - build.queue.size() - build.claimed.size()) / (float) build.total));
        }
        return anyone && (!build.queue.isEmpty() || !build.claimed.isEmpty());
    }

    private static void work(ServerLevel level, Mob worker, Build build, long now) {
        UUID id = worker.getUUID();
        Piece piece = build.claimed.get(id);
        if (piece == null) {
            piece = claim(level, build);
            if (piece == null) {
                return;
            }
            build.claimed.put(id, piece);
            build.claimedAt.put(id, now);
            build.working.put(id, 0);
        }
        BlockPos pos = place(build.origin, build.facing, piece);
        double distance = worker.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distance > REACH * REACH) {
            if (now - build.claimedAt.get(id) > REACH_TICKS) {
                build.claimed.remove(id); // cannot get there: leave this block
                return;
            }
            if (now % 10 == 0) {
                worker.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.6);
            }
            return;
        }
        worker.getNavigation().stop();
        worker.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        int ticks = build.working.merge(id, 1, Integer::sum);
        if (ticks % 4 == 1) {
            worker.swing(InteractionHand.MAIN_HAND);
        }
        if (ticks < PLACE_TICKS) {
            return;
        }
        build.claimed.remove(id);
        if (put(level, build, piece, pos)) {
            build.placed++;
        }
    }

    /** The next piece that still needs doing and has material; pieces that cannot be done are dropped. */
    private static Piece claim(ServerLevel level, Build build) {
        while (!build.queue.isEmpty()) {
            Piece piece = build.queue.remove(0);
            BlockPos pos = place(build.origin, build.facing, piece);
            if (!free(level, pos, piece.role(), true)) {
                continue;
            }
            if (material(build.stock, piece.role()).isEmpty()) {
                if (piece.role() != Role.DOOR && piece.role() != Role.TORCH && piece.role() != Role.WATER
                        && piece.role() != Role.CROP) {
                    build.missing.add(piece.role()); // a door, torch, water or seeds are optional extras
                }
                continue;
            }
            return piece;
        }
        return null;
    }

    /** Whether a piece can still go here: air or plants above ground, or grass/dirt to work. */
    private static boolean free(ServerLevel level, BlockPos pos, Role role, boolean checkEntities) {
        BlockState state = level.getBlockState(pos);
        if (role == Role.PATH || role == Role.FARMLAND || role == Role.WATER) {
            return (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                    || state.is(Blocks.PODZOL) || state.is(Blocks.ROOTED_DIRT))
                    && level.getBlockState(pos.above()).canBeReplaced();
        }
        if (role == Role.CROP) {
            return state.isAir();
        }
        if (!state.canBeReplaced()) {
            return false;
        }
        return !checkEntities || level.getEntitiesOfClass(LivingEntity.class, new AABB(pos)).isEmpty();
    }

    /** Places one piece from the stock. */
    private static boolean put(ServerLevel level, Build build, Piece piece, BlockPos pos) {
        Role role = piece.role();
        if (!free(level, pos, role, true)) {
            return false;
        }
        BlockState state;
        switch (role) {
            case PATH -> state = Blocks.DIRT_PATH.defaultBlockState();
            case FARMLAND -> state = Blocks.FARMLAND.defaultBlockState();
            default -> {
                Optional<ItemStack> material = material(build.stock, role);
                if (material.isEmpty()) {
                    return false;
                }
                ItemStack stack = material.get();
                if (role == Role.WATER) {
                    state = Blocks.WATER.defaultBlockState();
                    build.stock.remove(stack);
                    build.stock.add(new ItemStack(Items.BUCKET));
                } else {
                    Block block = ((BlockItem) stack.getItem()).getBlock();
                    state = stateFor(level, block, pos, build.facing);
                    if (!state.canSurvive(level, pos)) {
                        return false;
                    }
                    stack.shrink(1);
                    build.stock.removeIf(ItemStack::isEmpty);
                }
            }
        }
        if (role == Role.DOOR) {
            level.setBlock(pos, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
            level.setBlock(pos.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        } else {
            level.setBlock(pos, state, Block.UPDATE_ALL);
        }
        level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 0.9f);
        return true;
    }

    private static BlockState stateFor(ServerLevel level, Block block, BlockPos pos, Direction facing) {
        BlockState state = block.defaultBlockState();
        if (block instanceof DoorBlock) {
            return state.setValue(DoorBlock.FACING, facing); // as if placed by someone walking in
        }
        if (block instanceof FenceGateBlock) {
            return state.setValue(FenceGateBlock.FACING, facing);
        }
        if (block instanceof StairBlock) {
            return state.setValue(StairBlock.FACING, facing);
        }
        return Block.updateFromNeighbourShapes(state, level, pos);
    }

    private static void finish(MinecraftServer server, Build build) {
        build.bar.removeAllPlayers();
        ServerPlayer player = server.getPlayerList().getPlayer(build.player);
        Entity leader = player == null ? null : player.serverLevel().getEntity(build.leader);
        for (ItemStack stack : build.stock) {
            if (stack.isEmpty()) {
                continue;
            }
            if (player != null && !player.getInventory().add(stack) && !stack.isEmpty()) {
                player.drop(stack, false);
            } else if (player == null && leader != null) {
                leader.spawnAtLocation(stack);
            }
        }
        if (player == null) {
            return;
        }
        for (UUID id : build.workers) {
            Entity worker = player.serverLevel().getEntity(id);
            if (worker != null && worker.isAlive()) {
                McaHandles.runInteraction(worker, player, "stopworking");
                McaHandles.runInteraction(worker, player, "MOVE");
            }
        }
        if (leader != null && leader.isAlive()) {
            String name = name(leader);
            if (!build.missing.isEmpty()) {
                Set<String> what = new LinkedHashSet<>();
                build.missing.forEach(r -> what.add(r.name().toLowerCase(java.util.Locale.ROOT)));
                AiLines.say(leader, player, AiLines.variant("build_short", Component.translatable(
                        "mcaconversations.ai.build.role." + what.iterator().next())), name, AiEmotion.NEUTRAL,
                        VoiceIntent.STATEMENT);
            } else {
                AiLines.say(leader, player, AiLines.variant("build_done",
                        Component.translatable("mcaconversations.ai.build." + build.template)), name, AiEmotion.PROUD,
                        VoiceIntent.STATEMENT);
            }
        }
    }

    private static String name(Entity e) {
        return McaCompat.getVillagerName(e).orElse(e.getName().getString());
    }

    private static void say(Entity villager, ServerPlayer player, String key, String name) {
        AiLines.say(villager, player, AiLines.variant(key), name, AiEmotion.NEUTRAL, VoiceIntent.STATEMENT);
    }

    /** The player is leaving: the build stops and what is left of the materials goes back to them. */
    static void forgetPlayer(ServerPlayer player) {
        BUILDS.removeIf(b -> {
            if (b.player.equals(player.getUUID())) {
                finish(player.getServer(), b);
                return true;
            }
            return false;
        });
    }

    static void reset() {
        BUILDS.forEach(b -> b.bar.removeAllPlayers());
        BUILDS.clear();
    }
}
