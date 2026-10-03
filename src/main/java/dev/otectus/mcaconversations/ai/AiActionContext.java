package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import dev.otectus.mcaconversations.conversation.AgeGroup;
import dev.otectus.mcaconversations.conversation.RelationshipBand;
import dev.otectus.mcaconversations.conversation.RelationshipRoles;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a villager could do for this player right now, and what it has to do it with. Decides which
 * spoken actions are offered to the model (exactly these, nothing else, can be taken), and writes the
 * prompt lines that let the villager answer about its own work and belongings truthfully.
 */
final class AiActionContext {

    /** What one turn knows about actions. */
    record Snapshot(Set<String> actions, Set<String> chores, List<AiContextSection> sections, List<String> offers) {
    }

    private AiActionContext() {
    }

    static Snapshot capture(Entity villager, ServerPlayer player, String villagerName, String playerName,
                            RelationshipBand band, RelationshipRoles roles, boolean grudge) {
        AgeGroup age = McaCompat.ageGroup(villager);
        boolean adultish = age == AgeGroup.ADULT || age == AgeGroup.TEEN;
        boolean family = roles.any();
        boolean trusted = family || band.isAtLeast(RelationshipBand.FRIEND);
        boolean trader = villager instanceof Villager v && !v.isBaby()
                && v.getVillagerData().getProfession() != VillagerProfession.NONE
                && v.getVillagerData().getProfession() != VillagerProfession.NITWIT;
        Container inventory = McaHandles.inventory(villager);
        ItemStack held = player.getMainHandItem();
        String currentChore = McaCompat.getCurrentChore(villager).orElse("NONE").toUpperCase(java.util.Locale.ROOT);
        boolean working = !currentChore.equals("NONE");

        EnumSet<AiActionKind> actions = EnumSet.noneOf(AiActionKind.class);
        if (trader) {
            actions.add(AiActionKind.TRADE);
        }
        if (!held.isEmpty()) {
            actions.add(AiActionKind.GIFT);
        }
        if (adultish || family) {
            actions.add(AiActionKind.FOLLOW);
            actions.add(AiActionKind.STAY);
            actions.add(AiActionKind.MOVE);
            actions.add(AiActionKind.GO_HOME);
        }
        if (trusted) {
            actions.add(AiActionKind.INVENTORY);
        }
        if (trusted && adultish) {
            actions.add(AiActionKind.ARMOR);
            actions.add(AiActionKind.WORK);
        }
        if (working) {
            actions.add(AiActionKind.STOP_WORK);
        }
        Map<String, Integer> carried = carried(inventory);
        if (band.isAtLeast(RelationshipBand.ACQUAINTANCE) && !carried.isEmpty()) {
            actions.add(AiActionKind.GIVE);
        }
        Set<String> chores = actions.contains(AiActionKind.WORK)
                ? EnumSet.allOf(AiChore.class).stream().map(AiChore::key).collect(Collectors.toSet()) : Set.of();

        // --- prompt lines ----------------------------------------------------------------------------
        List<String> lines = new ArrayList<>();
        lines.add(playerName + " is holding: " + (held.isEmpty() ? "nothing" : held.getCount() + " " + words(held)));
        List<String> tools = new ArrayList<>();
        for (AiChore chore : AiChore.values()) {
            if (AiWork.hasTool(villager, chore)) {
                tools.add(chore.tool() + " (can go " + chore.mcaCommand() + ")");
            }
        }
        lines.add("Your tools: " + (tools.isEmpty() ? "none" : String.join(", ", tools)));
        lines.add("You carry: " + (carried.isEmpty() ? "nothing" : carried.entrySet().stream().limit(10)
                .map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "))));
        AiWork.progressText(villager).ifPresentOrElse(p -> lines.add("Your current task: " + p),
                () -> {
                    if (working) {
                        lines.add("Your current task: " + currentChore.toLowerCase(java.util.Locale.ROOT));
                    }
                });
        List<AiContextSection> sections = List.of(new AiContextSection("Your work and belongings", lines));

        // --- the action menu shown to the model ---------------------------------------------------------
        List<String> offers = new ArrayList<>();
        if (!actions.isEmpty() && !grudge) {
            String kinds = actions.stream().map(AiActionKind::key).collect(Collectors.joining("|"));
            offers.add("{\"type\": \"action\", \"do\": \"" + kinds + "\"} when " + playerName + " asks you, in any words, "
                    + "to do one of these and you agree: trade = open your trade window; gift = take what " + playerName
                    + " is holding as a gift; inventory = open your inventory so " + playerName + " can hand you things "
                    + "(a tool, say) or take them; follow / stay / move (move freely) / go_home / armor (put armour on or "
                    + "off); stop_work. Do not use one the player did not ask for.");
            if (actions.contains(AiActionKind.WORK)) {
                offers.add("{\"type\": \"action\", \"do\": \"work\", \"task\": \"chop|harvest|hunt|fish|mine\", "
                        + "\"amount\": 0-64} when sent to work (chop = cut trees, harvest = farm crops, hunt, fish, "
                        + "mine = dig stone and ore). amount = how much to bring back (0 = until told to stop). "
                        + "Each needs its tool in your inventory; without it, say so and ask for one.");
            }
            if (actions.contains(AiActionKind.GIVE)) {
                offers.add("{\"type\": \"action\", \"do\": \"give\", \"item\": \"minecraft:item_id\", \"amount\": 1-64} "
                        + "to hand " + playerName + " something you carry (only what you carry: "
                        + String.join(", ", carriedIds(inventory)) + ")");
            }
        } else if (grudge) {
            offers.add("{\"type\": \"action\", \"do\": \"move|go_home|stop_work\"} only; you do no favours while hurt");
        }
        return new Snapshot(actions.stream().map(AiActionKind::key).collect(Collectors.toSet()), chores, sections, offers);
    }

    private static Map<String, Integer> carried(Container inventory) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (inventory != null) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    out.merge(words(stack), stack.getCount(), Integer::sum);
                }
            }
        }
        return out;
    }

    private static List<String> carriedIds(Container inventory) {
        List<String> out = new ArrayList<>();
        if (inventory != null) {
            for (int i = 0; i < inventory.getContainerSize() && out.size() < 12; i++) {
                ItemStack stack = inventory.getItem(i);
                String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
                if (!stack.isEmpty() && !out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static String words(ItemStack stack) {
        return AiContextFormat.words(String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
    }
}
