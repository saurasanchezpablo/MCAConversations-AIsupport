package dev.otectus.mcaconversations.ai;

import com.mojang.datafixers.util.Pair;
import dev.otectus.mcaconversations.McaConversations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Optional;

/**
 * A villager telling the player the way: to a building of their own village (from MCA's village
 * map), or, for a friend, to a structure MCA's villagers spread rumours about. The answer is the
 * game's, not the model's: a compass direction and a distance, plus rough coordinates for a far
 * structure, spoken by the villager after their reply.
 */
final class AiDirections {

    /** How far a structure search reaches, in chunks (exploration maps use 100). */
    static final int SEARCH_RADIUS_CHUNKS = 48;
    /** Least ticks between two structure searches on the whole server; a search can be costly. */
    static final long SEARCH_COOLDOWN_TICKS = 200;
    private static final long NEVER = Long.MIN_VALUE;
    private static long lastSearch = NEVER;
    /** The directions follow the villager's reply, never precede it. */
    static final long LINE_DELAY_TICKS = 20;

    private AiDirections() {
    }

    static void give(ServerLevel level, Entity villager, ServerPlayer player, AiSocial.Place place, String villagerName,
                     AiPairMemory pair, long now, long day) {
        try {
            if (place.building().isPresent()) {
                BlockPos target = place.building().get();
                // MCA names its building types in every language it ships; the English label is the fallback.
                Component name = Component.translatableWithFallback("buildingType." + place.token(), place.label());
                AiLines.sayLater(villager, player, AiLines.variant("directions_building", name,
                        compass(villager.blockPosition(), target), blocks(villager.blockPosition(), target)), villagerName,
                        now, LINE_DELAY_TICKS);
                return;
            }
            // Someone else's search just ran: this one waits, and the pair keeps today's directions.
            if (place.structure().isEmpty() || !searchReady(lastSearch, now) || !pair.claimDirections(day)) {
                return;
            }
            lastSearch = now;
            Optional<BlockPos> found = locate(level, villager.blockPosition(), place);
            if (found.isPresent()) {
                BlockPos target = found.get();
                AiLines.sayLater(villager, player, AiLines.variant("directions_structure", place.label(),
                        compass(villager.blockPosition(), target), blocks(villager.blockPosition(), target),
                        target.getX(), target.getZ()), villagerName, now, LINE_DELAY_TICKS);
            } else {
                AiLines.sayLater(villager, player, AiLines.variant("directions_unknown", place.label()), villagerName,
                        now, LINE_DELAY_TICKS);
            }
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("AI directions failed", t);
        }
    }

    /** Whether the server-wide search cooldown has passed (a clock set back counts as passed). Pure. */
    static boolean searchReady(long last, long now) {
        return last == NEVER || now < last || now - last >= SEARCH_COOLDOWN_TICKS;
    }

    /** Server stopped: the next world starts with no search behind it. */
    static void reset() {
        lastSearch = NEVER;
    }

    private static Optional<BlockPos> locate(ServerLevel level, BlockPos from, AiSocial.Place place) {
        ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE, place.structure().get());
        Optional<? extends Holder<Structure>> holder = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getHolder(key);
        if (holder.isEmpty()) {
            return Optional.empty();
        }
        Pair<BlockPos, Holder<Structure>> result = level.getChunkSource().getGenerator().findNearestMapStructure(
                level, HolderSet.direct(holder.get()), from, SEARCH_RADIUS_CHUNKS, false);
        return result == null ? Optional.empty() : Optional.of(result.getFirst());
    }

    /** One of eight compass points, as a translatable word, from {@code from} toward {@code to}. */
    static Component compass(BlockPos from, BlockPos to) {
        return Component.translatable("mcaconversations.ai.compass." + compassKey(to.getX() - from.getX(), to.getZ() - from.getZ()));
    }

    /** Pure: Minecraft's north is -Z and east is +X. */
    static String compassKey(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return "here";
        }
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        int sector = (int) Math.floorMod(Math.round(angle / 45.0), 8);
        return new String[]{"n", "ne", "e", "se", "s", "sw", "w", "nw"}[sector];
    }

    /** Distance rounded to a figure a person would say: nearest 5 up close, nearest 50 far away. */
    static int blocks(BlockPos from, BlockPos to) {
        double d = Math.sqrt(from.distSqr(to));
        int step = d < 100 ? 5 : 50;
        return (int) Math.max(step, Math.round(d / step) * step);
    }
}
