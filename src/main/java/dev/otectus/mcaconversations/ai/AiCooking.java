package dev.otectus.mcaconversations.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cooking and smelting for a villager's errand, by the game's own recipes: a smoker cooks food, a
 * blast furnace smelts ores and metal, a furnace does both, each at its own speed. Fuel is the
 * player's (whatever burns among what they handed over) and then the villager's own; every item
 * cooked burns as much fuel as it would in a real furnace, and a fuel's container (a lava bucket's
 * bucket) comes back. Nothing is made from nothing.
 */
final class AiCooking {

    /** Raw burn time one item takes, whatever the station (a smoker burns fuel twice as fast, but cooks twice as fast). */
    static final int BURN_PER_ITEM = 200;

    enum Station {
        FURNACE("furnace"), SMOKER("smoker"), BLAST_FURNACE("blast furnace");

        final String words;

        Station(String words) {
            this.words = words;
        }

        RecipeType<?> recipeType() {
            return switch (this) {
                case FURNACE -> RecipeType.SMELTING;
                case SMOKER -> RecipeType.SMOKING;
                case BLAST_FURNACE -> RecipeType.BLASTING;
            };
        }

        static Optional<Station> of(BlockEntity entity) {
            if (entity instanceof SmokerBlockEntity) {
                return Optional.of(SMOKER);
            }
            if (entity instanceof BlastFurnaceBlockEntity) {
                return Optional.of(BLAST_FURNACE);
            }
            return entity instanceof FurnaceBlockEntity ? Optional.of(FURNACE) : Optional.empty();
        }
    }

    record Found(BlockPos pos, Station station) {
    }

    /** What one trip to the station turns into. */
    record Plan(List<ItemStack> results, List<ItemStack> leftovers, int cooked, int ticks, float experience) {
    }

    private AiCooking() {
    }

    /** Items of {@code count} a burn budget covers. Pure. */
    static int coverable(int burn) {
        return Math.max(0, burn) / BURN_PER_ITEM;
    }

    /** Every cooking station within {@code radius} blocks (a few blocks up and down), nearest first. */
    static List<Found> stations(ServerLevel level, BlockPos from, int radius) {
        List<Found> out = new ArrayList<>();
        for (int cx = (from.getX() - radius) >> 4; cx <= (from.getX() + radius) >> 4; cx++) {
            for (int cz = (from.getZ() - radius) >> 4; cz <= (from.getZ() + radius) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    BlockPos pos = entity.getBlockPos();
                    if (Math.abs(pos.getX() - from.getX()) <= radius && Math.abs(pos.getZ() - from.getZ()) <= radius
                            && Math.abs(pos.getY() - from.getY()) <= 6) {
                        Station.of(entity).ifPresent(s -> out.add(new Found(pos.immutable(), s)));
                    }
                }
            }
        }
        out.sort(java.util.Comparator.comparingDouble(f -> f.pos().distSqr(from)));
        return out;
    }

    static Optional<AbstractCookingRecipe> recipe(ServerLevel level, Station station, ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        SingleRecipeInput input = new SingleRecipeInput(stack);
        var manager = level.getRecipeManager();
        Optional<? extends RecipeHolder<? extends AbstractCookingRecipe>> holder = switch (station) {
            case FURNACE -> manager.getRecipeFor(RecipeType.SMELTING, input, level);
            case SMOKER -> manager.getRecipeFor(RecipeType.SMOKING, input, level);
            case BLAST_FURNACE -> manager.getRecipeFor(RecipeType.BLASTING, input, level);
        };
        return holder.map(RecipeHolder::value);
    }

    /** The station near {@code from} that can cook the most of {@code inputs}; nearer wins a tie. */
    static Optional<Found> best(ServerLevel level, BlockPos from, int radius, List<ItemStack> inputs) {
        Found best = null;
        int bestCount = 0;
        for (Found found : stations(level, from, radius)) {
            int count = 0;
            for (ItemStack stack : inputs) {
                if (recipe(level, found.station(), stack).isPresent()) {
                    count += stack.getCount();
                }
            }
            if (count > bestCount) {
                best = found;
                bestCount = count;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Works out the batch: what cooks, the fuel it burns (the player's first, then the villager's), how
     * long it takes and what comes back. Takes the fuel it burns out of {@code villagerInventory}.
     */
    static Plan plan(ServerLevel level, Station station, List<ItemStack> inputs, Container villagerInventory) {
        List<ItemStack> cook = new ArrayList<>();
        List<ItemStack> fuel = new ArrayList<>();
        List<ItemStack> leftovers = new ArrayList<>();
        RecipeType<?> type = station.recipeType();
        for (ItemStack stack : inputs) {
            if (stack.isEmpty()) {
                continue;
            }
            if (recipe(level, station, stack).isPresent()) {
                cook.add(stack.copy());
            } else if (stack.getBurnTime(type) > 0) {
                fuel.add(stack.copy());
            } else {
                leftovers.add(stack.copy());
            }
        }
        int wanted = cook.stream().mapToInt(ItemStack::getCount).sum();
        int need = wanted * BURN_PER_ITEM;
        int burn = burn(fuel, need, type, leftovers);
        if (burn < need && villagerInventory != null) {
            for (int i = 0; i < villagerInventory.getContainerSize() && burn < need; i++) {
                ItemStack own = villagerInventory.getItem(i);
                if (!own.isEmpty() && !own.isDamageableItem() && own.getBurnTime(type) > 0
                        && recipe(level, station, own).isEmpty()) {
                    List<ItemStack> one = new ArrayList<>(List.of(own));
                    burn += burn(one, need - burn, type, leftovers);
                    villagerInventory.setItem(i, one.isEmpty() ? ItemStack.EMPTY : one.get(0));
                }
            }
            villagerInventory.setChanged();
        }
        leftovers.addAll(fuel.stream().filter(s -> !s.isEmpty()).toList());

        int canCook = coverable(burn);
        List<ItemStack> results = new ArrayList<>();
        int cooked = 0;
        int ticks = 0;
        float experience = 0;
        for (ItemStack raw : cook) {
            AbstractCookingRecipe recipe = recipe(level, station, raw).orElse(null);
            int n = recipe == null ? 0 : Math.min(raw.getCount(), canCook - cooked);
            if (n > 0) {
                ItemStack out = recipe.assemble(new SingleRecipeInput(raw), level.registryAccess());
                out.setCount(Math.min(out.getMaxStackSize() * 4, out.getCount() * n));
                while (out.getCount() > out.getMaxStackSize()) {
                    results.add(out.split(out.getMaxStackSize()));
                }
                results.add(out);
                cooked += n;
                ticks += recipe.getCookingTime() * n;
                experience += recipe.getExperience() * n;
                raw.shrink(n);
            }
            if (!raw.isEmpty()) {
                leftovers.add(raw);
            }
        }
        return new Plan(results, leftovers, cooked, ticks, experience);
    }

    /**
     * Burns fuel items one at a time from {@code fuel} until {@code need} is met; empties what it uses
     * (removing spent stacks) and puts any container a fuel leaves behind into {@code leftovers}.
     */
    private static int burn(List<ItemStack> fuel, int need, RecipeType<?> type, List<ItemStack> leftovers) {
        int burn = 0;
        for (ItemStack stack : fuel) {
            while (burn < need && !stack.isEmpty()) {
                burn += stack.getBurnTime(type);
                if (stack.hasCraftingRemainingItem()) {
                    leftovers.add(stack.getCraftingRemainingItem().copy());
                }
                stack.shrink(1);
            }
        }
        fuel.removeIf(ItemStack::isEmpty);
        return burn;
    }
}
