package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.chat.VillagerAttention;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import dev.otectus.mcaconversations.voice.VoiceIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Errands a player sends a villager on by word, driven by this mod: guiding the player somewhere,
 * going to a place to wait, picking up what lies around, storing or fetching things from a chest,
 * feeding animals so they breed, and cooking or smelting at a furnace, smoker or blast furnace.
 *
 * <p>While on an errand the villager is put on MCA's "prospecting" chore, which has no task in MCA,
 * so MCA's brain stands aside while this class walks the villager about. Errands that collect
 * something end the way work does: the villager comes back to the player and hands it over. The
 * player who asked sees a boss bar. Server thread only; errands are not saved.
 */
final class AiErrands {

    static final double ARRIVE = 2.5;
    /** A guide waits when the player falls this far behind. */
    static final double GUIDE_WAIT = 10.0;
    static final int SEARCH_RADIUS = 14;
    static final long TIMEOUT_TICKS = 3600;
    static final long RETURN_TIMEOUT_TICKS = 1200;

    enum Stage { GO, COOK, DELIVER }

    static final class Errand {
        final UUID villager;
        final UUID player;
        final AiActionKind kind;
        final String label;
        final BlockPos place;
        final String item;
        final int amount;
        final ServerBossEvent bar;
        final List<ItemStack> carried = new ArrayList<>();
        /** For COOK: what the player handed over, until it goes into the station. */
        final List<ItemStack> inputs = new ArrayList<>();
        AiCooking.Station station;
        long cookUntil;
        int cookTicks;
        float experience;
        final long started;
        Stage stage = Stage.GO;
        long stageSince;
        int done;
        Entity targetEntity;
        BlockPos targetBlock;

        Errand(UUID villager, UUID player, AiActionKind kind, String label, BlockPos place, String item, int amount,
               ServerBossEvent bar, long now) {
            this.villager = villager;
            this.player = player;
            this.kind = kind;
            this.label = label;
            this.place = place;
            this.item = item;
            this.amount = amount;
            this.bar = bar;
            this.started = now;
            this.stageSince = now;
        }
    }

    private static final Map<UUID, Errand> ERRANDS = new HashMap<>();

    private AiErrands() {
    }

    // --- what is around, for offering errands ---------------------------------------------------

    /** The nearest chest-like container around a position, if any. */
    static Optional<BlockPos> nearestContainer(ServerLevel level, BlockPos from, int radius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(from.offset(-radius, -4, -radius), from.offset(radius, 4, radius))) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof BaseContainerBlockEntity && from.distSqr(pos) < bestDistance) {
                best = pos.immutable();
                bestDistance = from.distSqr(pos);
            }
        }
        return Optional.ofNullable(best);
    }

    static List<ItemEntity> looseItems(ServerLevel level, BlockPos from) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(from).inflate(SEARCH_RADIUS),
                e -> e.isAlive() && !e.getItem().isEmpty());
    }

    /** Animals around that could breed and that the villager has food for. */
    static List<Animal> feedable(ServerLevel level, Entity villager) {
        Container inventory = McaHandles.inventory(villager);
        if (inventory == null) {
            return List.of();
        }
        return level.getEntitiesOfClass(Animal.class, villager.getBoundingBox().inflate(SEARCH_RADIUS),
                a -> a.isAlive() && !a.isBaby() && a.canFallInLove() && foodSlot(inventory, a) >= 0);
    }

    private static int foodSlot(Container inventory, Animal animal) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && animal.isFood(stack)) {
                return i;
            }
        }
        return -1;
    }

    // --- starting and stopping --------------------------------------------------------------------

    static boolean start(Entity villager, ServerPlayer player, AiEffect.Action action, AiSocial.Turn turn,
                         String villagerName, long now) {
        return start(villager, player, action, turn, villagerName, true, now);
    }

    /** As above; a helper in a group errand works without a bar of its own (the leader's shows the errand). */
    static boolean start(Entity villager, ServerPlayer player, AiEffect.Action action, AiSocial.Turn turn,
                         String villagerName, boolean showBar, long now) {
        BlockPos place = null;
        String label = action.kind().key();
        if (action.kind() == AiActionKind.GUIDE || action.kind() == AiActionKind.WAIT_AT) {
            AiSocial.Place target = turn.places().get(action.place());
            if (target == null || target.building().isEmpty()) {
                return false; // only places in the village, which have a known position
            }
            place = target.building().get();
            label = target.label();
        }
        stop(villager.getUUID());
        AiWork.stop(villager.getUUID(), true);
        VillagerAttention.release(villager);
        McaHandles.runInteraction(villager, player, AiChore.MINE.mcaCommand()); // MCA's brain stands aside
        ServerBossEvent bar = new ServerBossEvent(title(villagerName, action.kind(), label, 0, action.amount()),
                BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
        if (showBar) {
            bar.addPlayer(player);
        }
        ERRANDS.put(villager.getUUID(), new Errand(villager.getUUID(), player.getUUID(), action.kind(), label, place,
                action.item(), action.amount(), bar, now));
        return true;
    }

    /**
     * Sends a villager to cook or smelt what the player handed over, at the nearby station that can
     * cook the most of it. False when there is none (the caller gives the items back).
     */
    static boolean startCook(Entity villager, ServerPlayer player, List<ItemStack> inputs, String villagerName, long now) {
        ServerLevel level = player.serverLevel();
        Optional<AiCooking.Found> found = AiCooking.best(level, villager.blockPosition(), SEARCH_RADIUS, inputs);
        if (found.isEmpty()) {
            say(villager, player, "cook_nothing", villagerName, "");
            return false;
        }
        stop(villager.getUUID());
        AiWork.stop(villager.getUUID(), true);
        VillagerAttention.release(villager);
        McaHandles.runInteraction(villager, player, AiChore.MINE.mcaCommand()); // MCA's brain stands aside
        String label = found.get().station().words;
        ServerBossEvent bar = new ServerBossEvent(title(villagerName, AiActionKind.COOK, label, 0, 0),
                BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
        bar.addPlayer(player);
        Errand errand = new Errand(villager.getUUID(), player.getUUID(), AiActionKind.COOK, label, found.get().pos(), "", 0,
                bar, now);
        errand.station = found.get().station();
        errand.targetBlock = found.get().pos();
        errand.inputs.addAll(inputs);
        ERRANDS.put(villager.getUUID(), errand);
        return true;
    }

    static boolean busy(UUID villager) {
        return ERRANDS.containsKey(villager);
    }

    static void stop(UUID villager) {
        Errand errand = ERRANDS.remove(villager);
        if (errand != null) {
            errand.bar.removeAllPlayers();
            refund(errand, null, null);
        }
    }

    /**
     * Whatever an errand still holds for the player (things to cook, things gathered) is never lost
     * when it ends early: handed to the player if they are online, else left where the villager stands.
     */
    private static void refund(Errand errand, ServerPlayer player, Entity villager) {
        List<ItemStack> goods = new ArrayList<>(errand.inputs);
        goods.addAll(errand.carried);
        errand.inputs.clear();
        errand.carried.clear();
        for (ItemStack stack : goods) {
            if (stack.isEmpty()) {
                continue;
            }
            if (player != null && !player.hasDisconnected()) {
                if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                    player.drop(stack, false);
                }
            } else if (villager != null && villager.isAlive()) {
                villager.spawnAtLocation(stack);
            }
        }
    }

    static Optional<String> progressText(Entity villager) {
        Errand e = ERRANDS.get(villager.getUUID());
        return e == null ? Optional.empty() : Optional.of(e.kind.key() + " (" + e.label + ")"
                + (e.stage == Stage.DELIVER ? ", bringing it back" : "") + (e.done > 0 ? ", " + e.done + " so far" : ""));
    }

    private static Component title(String name, AiActionKind kind, String label, int done, int amount) {
        return Component.literal(name + " - ").append(Component.translatable("mcaconversations.ai.errand." + kind.key(), label))
                .append(Component.literal(done > 0 || amount > 0 ? ": " + done + (amount > 0 ? "/" + amount : "") : ""));
    }

    // --- every tick ---------------------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        if (ERRANDS.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (Iterator<Errand> it = ERRANDS.values().iterator(); it.hasNext(); ) {
            Errand errand = it.next();
            boolean keep;
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(errand.player);
                Entity entity = player == null ? null : player.serverLevel().getEntity(errand.villager);
                boolean timedOut = now - errand.started >= TIMEOUT_TICKS + errand.cookTicks;
                keep = player != null && entity instanceof Mob villager && villager.isAlive() && !timedOut
                        && step(player.serverLevel(), villager, player, errand, now);
                if (timedOut && player != null && entity != null && entity.isAlive()) {
                    // Never a silent give-up: the villager says it did not work out.
                    say(entity, player, "errand_gave_up",
                            McaCompat.getVillagerName(entity).orElse(entity.getName().getString()), "");
                }
                if (!keep && player != null && entity != null && entity.isAlive()) {
                    McaHandles.runInteraction(entity, player, "stopworking");
                }
                if (!keep) {
                    refund(errand, player, entity);
                }
            } catch (Throwable t) {
                McaConversations.LOGGER.debug("AI errand failed; dropping it", t);
                keep = false;
            }
            if (!keep) {
                errand.bar.removeAllPlayers();
                it.remove();
            }
        }
    }

    /** One tick of an errand. Returns false when it is over. */
    private static boolean step(ServerLevel level, Mob villager, ServerPlayer player, Errand e, long now) {
        String name = McaCompat.getVillagerName(villager).orElse(villager.getName().getString());
        if (now % 20 == 0) {
            e.bar.setName(title(name, e.kind, e.label, e.done, e.amount));
            if (e.amount > 0) {
                e.bar.setProgress(Math.min(1f, e.done / (float) e.amount));
            }
        }
        if (e.stage == Stage.DELIVER) {
            return deliver(villager, player, e, name, now);
        }
        if (e.stage == Stage.COOK) {
            return cooking(level, villager, player, e, now);
        }
        return switch (e.kind) {
            case GUIDE -> guide(villager, player, e, name, now);
            case WAIT_AT -> waitAt(villager, player, e, name, now);
            case PICK_UP -> pickUp(level, villager, player, e, now);
            case STORE -> store(level, villager, player, e, name, now);
            case FETCH -> fetch(level, villager, player, e, name, now);
            case BREED -> breed(level, villager, player, e, name, now);
            case COOK -> goCook(level, villager, player, e, name, now);
            default -> false;
        };
    }

    private static boolean walk(Mob villager, double x, double y, double z, double speed, long now) {
        double distance = villager.distanceToSqr(x, y, z);
        if (distance <= ARRIVE * ARRIVE) {
            villager.getNavigation().stop();
            return true;
        }
        if (now % 10 == 0) {
            villager.getNavigation().moveTo(x, y, z, speed);
        }
        return false;
    }

    private static boolean guide(Mob villager, ServerPlayer player, Errand e, String name, long now) {
        if (villager.distanceTo(player) > GUIDE_WAIT) {
            villager.getNavigation().stop();
            villager.getLookControl().setLookAt(player);
            return true; // wait for the player to catch up
        }
        e.bar.setProgress(1f - (float) Math.min(1.0, Math.sqrt(villager.blockPosition().distSqr(e.place)) / 200.0));
        if (walk(villager, e.place.getX() + 0.5, e.place.getY(), e.place.getZ() + 0.5, 0.5, now)) {
            say(villager, player, "guide_arrived", name, e.label);
            return false;
        }
        return true;
    }

    private static boolean waitAt(Mob villager, ServerPlayer player, Errand e, String name, long now) {
        if (walk(villager, e.place.getX() + 0.5, e.place.getY(), e.place.getZ() + 0.5, 0.6, now)) {
            McaHandles.runInteraction(villager, player, "stopworking");
            McaHandles.runInteraction(villager, player, "STAY");
            return false;
        }
        return true;
    }

    private static boolean pickUp(ServerLevel level, Mob villager, ServerPlayer player, Errand e, long now) {
        if (e.targetEntity == null || !e.targetEntity.isAlive()) {
            e.targetEntity = looseItems(level, villager.blockPosition()).stream()
                    .min(Comparator.comparingDouble(i -> i.distanceToSqr(villager))).orElse(null);
            if (e.targetEntity == null || (e.amount > 0 && e.done >= e.amount)) {
                return toDeliver(villager, player, e, now);
            }
        }
        ItemEntity item = (ItemEntity) e.targetEntity;
        if (walk(villager, item.getX(), item.getY(), item.getZ(), 0.6, now)) {
            ItemStack stack = item.getItem().copy();
            e.carried.add(stack);
            e.done += stack.getCount();
            item.discard();
            e.targetEntity = null;
        }
        return true;
    }

    private static boolean store(ServerLevel level, Mob villager, ServerPlayer player, Errand e, String name, long now) {
        if (e.targetBlock == null) {
            e.targetBlock = nearestContainer(level, villager.blockPosition(), SEARCH_RADIUS).orElse(null);
            if (e.targetBlock == null) {
                return false;
            }
        }
        if (!walk(villager, e.targetBlock.getX() + 0.5, e.targetBlock.getY(), e.targetBlock.getZ() + 0.5, 0.6, now)) {
            return true;
        }
        Container chest = level.getBlockEntity(e.targetBlock) instanceof Container c ? c : null;
        Container inventory = McaHandles.inventory(villager);
        if (chest != null && inventory != null) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty() || stack.isDamageableItem()) {
                    continue; // tools, weapons and armour stay with the villager
                }
                int before = stack.getCount();
                ItemStack rest = insert(chest, stack.copy());
                e.done += before - rest.getCount();
                inventory.setItem(i, rest);
            }
            chest.setChanged();
            inventory.setChanged();
        }
        say(villager, player, "stored", name, e.done);
        return false;
    }

    private static boolean fetch(ServerLevel level, Mob villager, ServerPlayer player, Errand e, String name, long now) {
        ResourceLocation id = ResourceLocation.tryParse(e.item);
        if (id == null) {
            return false;
        }
        if (e.targetBlock == null) {
            e.targetBlock = chestWith(level, villager.blockPosition(), id).orElse(null);
            if (e.targetBlock == null) {
                say(villager, player, "fetch_missing", name, Component.translatable(BuiltInRegistries.ITEM.get(id).getDescriptionId()));
                return false;
            }
        }
        if (!walk(villager, e.targetBlock.getX() + 0.5, e.targetBlock.getY(), e.targetBlock.getZ() + 0.5, 0.6, now)) {
            return true;
        }
        if (level.getBlockEntity(e.targetBlock) instanceof Container chest) {
            int want = Math.max(1, e.amount);
            for (int i = 0; i < chest.getContainerSize() && e.done < want; i++) {
                ItemStack stack = chest.getItem(i);
                if (!stack.isEmpty() && id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                    ItemStack taken = stack.split(Math.min(want - e.done, stack.getCount()));
                    e.carried.add(taken);
                    e.done += taken.getCount();
                }
            }
            chest.setChanged();
        }
        return toDeliver(villager, player, e, now);
    }

    private static boolean breed(ServerLevel level, Mob villager, ServerPlayer player, Errand e, String name, long now) {
        int goal = e.amount > 0 ? e.amount : 4;
        if (e.targetEntity == null || !(e.targetEntity instanceof Animal a) || !a.isAlive() || !a.canFallInLove()) {
            e.targetEntity = feedable(level, villager).stream()
                    .min(Comparator.comparingDouble(an -> an.distanceToSqr(villager))).orElse(null);
            if (e.targetEntity == null || e.done >= goal) {
                say(villager, player, "bred", name, e.done);
                return false;
            }
        }
        Animal animal = (Animal) e.targetEntity;
        if (walk(villager, animal.getX(), animal.getY(), animal.getZ(), 0.6, now)) {
            Container inventory = McaHandles.inventory(villager);
            int slot = inventory == null ? -1 : foodSlot(inventory, animal);
            if (slot >= 0) {
                inventory.getItem(slot).shrink(1);
                inventory.setChanged();
                animal.setInLove(player);
                villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                e.done++;
            }
            e.targetEntity = null;
        }
        return true;
    }

    private static boolean goCook(ServerLevel level, Mob villager, ServerPlayer player, Errand e, String name, long now) {
        BlockPos pos = e.targetBlock;
        if (!walk(villager, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.6, now)) {
            return true;
        }
        if (AiCooking.Station.of(level.getBlockEntity(pos)).filter(s -> s == e.station).isEmpty()) {
            say(villager, player, "cook_nothing", name, "");
            e.carried.addAll(e.inputs);
            e.inputs.clear();
            return toDeliver(villager, player, e, now);
        }
        AiCooking.Plan plan = AiCooking.plan(level, e.station, e.inputs, McaHandles.inventory(villager));
        e.inputs.clear();
        e.carried.addAll(plan.results());
        e.carried.addAll(plan.leftovers());
        if (plan.cooked() == 0) {
            say(villager, player, "cook_no_fuel", name, "");
            return toDeliver(villager, player, e, now);
        }
        e.cookTicks = plan.ticks();
        e.cookUntil = now + plan.ticks();
        e.experience = plan.experience();
        e.stage = Stage.COOK;
        e.stageSince = now;
        e.bar.setColor(BossEvent.BossBarColor.RED);
        return true;
    }

    /** Standing at the station while the batch cooks: smoke and flame, the bar filling. */
    private static boolean cooking(ServerLevel level, Mob villager, ServerPlayer player, Errand e, long now) {
        BlockPos pos = e.targetBlock;
        int cooked = e.carried.stream().mapToInt(ItemStack::getCount).sum();
        float progress = e.cookTicks <= 0 ? 1f : Math.min(1f, (now - e.stageSince) / (float) e.cookTicks);
        e.done = Math.round(progress * cooked);
        e.bar.setProgress(progress);
        if (villager.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) > 9) {
            walk(villager, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.6, now);
        }
        villager.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (now % 10 == 0) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 1.1,
                    pos.getZ() + 0.5, 2, 0.15, 0.05, 0.15, 0.01);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.4,
                    pos.getZ() + 0.5, 1, 0.25, 0.1, 0.25, 0.0);
        }
        if (now % 60 == 0) {
            villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        if (now < e.cookUntil) {
            return true;
        }
        e.done = cooked;
        return toDeliver(villager, player, e, now);
    }

    /** Collected something: stop the errand chore and come back to hand it over. */
    private static boolean toDeliver(Mob villager, ServerPlayer player, Errand e, long now) {
        if (e.carried.isEmpty()) {
            return false;
        }
        McaHandles.runInteraction(villager, player, "stopworking");
        McaHandles.runInteraction(villager, player, "FOLLOW");
        e.stage = Stage.DELIVER;
        e.stageSince = now;
        e.bar.setColor(BossEvent.BossBarColor.BLUE);
        return true;
    }

    private static boolean deliver(Mob villager, ServerPlayer player, Errand e, String name, long now) {
        if (villager.distanceTo(player) <= 3.5) {
            for (ItemStack stack : e.carried) {
                if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                    player.drop(stack, false);
                }
            }
            e.carried.clear();
            if (e.experience > 0) {
                int xp = (int) Math.floor(e.experience);
                if (Math.random() < e.experience - xp) {
                    xp++;
                }
                if (xp > 0) {
                    net.minecraft.world.entity.ExperienceOrb.award(player.serverLevel(), player.position(), xp);
                }
            }
            McaHandles.runInteraction(villager, player, "MOVE");
            say(villager, player, e.kind == AiActionKind.COOK ? "cooked" : "brought", name, e.done);
            return false;
        }
        if (now - e.stageSince > RETURN_TIMEOUT_TICKS) {
            // Never lose the goods: if the villager cannot reach the player, it leaves them where it stands.
            for (ItemStack stack : e.carried) {
                villager.spawnAtLocation(stack);
            }
            e.carried.clear();
            McaHandles.runInteraction(villager, player, "MOVE");
            return false;
        }
        return true;
    }

    private static Optional<BlockPos> chestWith(ServerLevel level, BlockPos from, ResourceLocation item) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(from.offset(-SEARCH_RADIUS, -4, -SEARCH_RADIUS),
                from.offset(SEARCH_RADIUS, 4, SEARCH_RADIUS))) {
            if (!(level.getBlockEntity(pos) instanceof BaseContainerBlockEntity chest) || from.distSqr(pos) >= bestDistance) {
                continue;
            }
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack stack = chest.getItem(i);
                if (!stack.isEmpty() && item.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                    best = pos.immutable();
                    bestDistance = from.distSqr(pos);
                    break;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Puts as much of {@code stack} into the container as fits; returns the rest. */
    static ItemStack insert(Container container, ItemStack stack) {
        if (container instanceof SimpleContainer simple) {
            return simple.addItem(stack);
        }
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, stack.copy());
                stack.setCount(0);
            } else if (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
                int move = Math.min(stack.getCount(), slot.getMaxStackSize() - slot.getCount());
                slot.grow(move);
                stack.shrink(move);
            }
        }
        return stack;
    }

    private static void say(Entity villager, ServerPlayer player, String key, String name, Object arg) {
        AiLines.say(villager, player, AiLines.variant(key, arg), name, AiEmotion.HAPPY, VoiceIntent.STATEMENT);
    }

    static void forgetPlayer(UUID player) {
        // The player is leaving: an errand ends, and its goods are left where the villager stands.
        ERRANDS.values().removeIf(e -> {
            if (e.player.equals(player)) {
                e.bar.removeAllPlayers();
                return true;
            }
            return false;
        });
    }

    /** As {@link #forgetPlayer}, giving back what an errand held for them while they are still here. */
    static void forgetPlayer(ServerPlayer player) {
        ERRANDS.values().removeIf(e -> {
            if (e.player.equals(player.getUUID())) {
                e.bar.removeAllPlayers();
                refund(e, player, null);
                return true;
            }
            return false;
        });
    }

    static void reset() {
        ERRANDS.values().forEach(e -> e.bar.removeAllPlayers());
        ERRANDS.clear();
    }
}
