package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.compat.McaCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a villager actually needs right now, read from the game rather than invented: food when hurt,
 * the tool or supplies of their trade when they have none, firewood when they live somewhere cold,
 * a bed when they have no home. The villager may bring it up and ask for help. If the player agrees,
 * it becomes a promise like any other, kept when the thing is handed over, so the help is real and
 * checked.
 *
 * <p>One need at a time, the most pressing, steady through the day so the villager does not ask for
 * something different every time.
 */
final class AiNeeds {

    /** Something needed: an item id or {@code #tag}, how many, and why, in the villager's words. */
    record Need(String item, int count, String why) {
    }

    /** What each trade cannot work without, by profession path. */
    static final Map<String, Need> TRADE_NEEDS = Map.ofEntries(
            Map.entry("farmer", new Need("#minecraft:hoes", 1, "your hoe is gone and the fields need tilling")),
            Map.entry("fisherman", new Need("minecraft:fishing_rod", 1, "you have no fishing rod")),
            Map.entry("shepherd", new Need("minecraft:shears", 1, "you have no shears for the sheep")),
            Map.entry("librarian", new Need("minecraft:paper", 6, "you have run out of paper for your books")),
            Map.entry("cartographer", new Need("minecraft:paper", 6, "you have no paper left to draw maps")),
            Map.entry("armorer", new Need("minecraft:coal", 8, "your forge has no coal")),
            Map.entry("toolsmith", new Need("minecraft:coal", 8, "your forge has no coal")),
            Map.entry("weaponsmith", new Need("minecraft:coal", 8, "your forge has no coal")),
            Map.entry("butcher", new Need("minecraft:coal", 4, "your smoker has nothing to burn")),
            Map.entry("cleric", new Need("minecraft:glass_bottle", 3, "you have no bottles for your remedies")),
            Map.entry("leatherworker", new Need("minecraft:leather", 4, "you are out of leather")),
            Map.entry("mason", new Need("minecraft:clay_ball", 8, "you are out of clay")),
            Map.entry("fletcher", new Need("minecraft:flint", 4, "you have no flint for arrowheads")));

    private AiNeeds() {
    }

    /** What this villager needs, most pressing first. */
    static Optional<Need> need(ServerLevel level, Entity villager) {
        List<Need> needs = new ArrayList<>();
        Container inventory = dev.otectus.mcaconversations.compat.mca.McaHandles.inventory(villager);
        if (villager instanceof LivingEntity living && living.getHealth() < living.getMaxHealth() * 0.6f) {
            needs.add(new Need("minecraft:bread", 3, "you are hurt and weak, and some good food would help you recover"));
        }
        String id = McaCompat.getProfessionId(villager).orElse("none").toLowerCase(Locale.ROOT);
        Need trade = TRADE_NEEDS.get(id.contains(":") ? id.substring(id.indexOf(':') + 1) : id);
        if (trade != null && count(inventory, trade.item()) == 0) {
            needs.add(trade);
        }
        if (level.getBiome(villager.blockPosition()).value().coldEnoughToSnow(villager.blockPosition())
                && count(inventory, "#minecraft:logs") < 4) {
            needs.add(new Need("#minecraft:logs", 8, "the nights here are freezing and you have no firewood"));
        }
        if (McaCompat.getHomePos(villager).isEmpty() && McaCompat.ageGroup(villager)
                == dev.otectus.mcaconversations.conversation.AgeGroup.ADULT) {
            needs.add(new Need("#minecraft:beds", 1, "you have no home and no bed of your own"));
        }
        return needs.stream().findFirst();
    }

    private static int count(Container inventory, String ref) {
        if (inventory == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (AiPromises.matches(ref, stack)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    /** How a need reads to the villager and the model. Pure. */
    static String describe(Need need) {
        return need.count() + " " + AiContextFormat.words(need.item().replace("#", "")) + " (" + need.why() + ")";
    }

    static List<String> promptLines(ServerLevel level, Entity villager, AiPairMemory pair, String playerName) {
        List<String> out = new ArrayList<>();
        need(level, villager).ifPresent(need -> {
            boolean promised = pair.promises().stream().anyMatch(p -> p.pending() && need.item().equals(p.item()));
            out.add("Right now you need " + describe(need) + "."
                    + (promised ? " " + playerName + " already promised to help with it."
                    : " If it comes up naturally, or " + playerName + " offers, you may ask for help; if they agree, "
                    + "record it as a promise for \"" + need.item() + "\"."));
        });
        return out;
    }

    /** A reason to come over and ask, unless the player already promised it. */
    static Optional<String> askReason(ServerLevel level, Entity villager, AiPairMemory pair, String playerName) {
        return need(level, villager)
                .filter(need -> pair.promises().stream().noneMatch(p -> p.pending() && need.item().equals(p.item())))
                .map(need -> "you need " + describe(need) + " and want to ask " + playerName + " for help");
    }
}
