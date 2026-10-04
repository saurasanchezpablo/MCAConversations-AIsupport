package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Teaching and learning, both ways.
 *
 * <p>Villagers get better at what they do. Every item they bring back from a task is practice, and a
 * player who takes the time to show them how ({@code teach}) speeds it up, once per task and day. A
 * more skilled miner digs faster, and every villager knows and talks about what they are good at.
 *
 * <p>Villagers know their trade, too. A farmer can show a player how to bake a cake, a librarian how
 * to make a lectern ({@code teach_recipe}). Only recipes from their trade that the player does not know
 * yet are offered, and the player's recipe book really learns them.
 */
final class AiSkills {

    static final int[] THRESHOLDS = {10, 30, 70, 150, 300};
    static final int TEACHING_XP = 20;
    static final int MAX_RECIPES_OFFERED = 4;

    /** What each trade can teach, by profession path. */
    static final Map<String, List<String>> TRADE_RECIPES = Map.ofEntries(
            Map.entry("farmer", List.of("minecraft:bread", "minecraft:cake", "minecraft:pumpkin_pie", "minecraft:cookie",
                    "minecraft:composter", "minecraft:hay_block")),
            Map.entry("fisherman", List.of("minecraft:fishing_rod", "minecraft:campfire", "minecraft:barrel", "minecraft:lead")),
            Map.entry("librarian", List.of("minecraft:bookshelf", "minecraft:lectern", "minecraft:writable_book",
                    "minecraft:book", "minecraft:paper")),
            Map.entry("cleric", List.of("minecraft:brewing_stand", "minecraft:glass_bottle", "minecraft:ender_eye",
                    "minecraft:fermented_spider_eye")),
            Map.entry("armorer", List.of("minecraft:blast_furnace", "minecraft:shield", "minecraft:iron_chestplate",
                    "minecraft:iron_helmet")),
            Map.entry("toolsmith", List.of("minecraft:smithing_table", "minecraft:anvil", "minecraft:iron_pickaxe",
                    "minecraft:shears")),
            Map.entry("weaponsmith", List.of("minecraft:grindstone", "minecraft:iron_sword", "minecraft:crossbow")),
            Map.entry("butcher", List.of("minecraft:smoker", "minecraft:rabbit_stew", "minecraft:beetroot_soup",
                    "minecraft:mushroom_stew")),
            Map.entry("leatherworker", List.of("minecraft:leather_chestplate", "minecraft:item_frame",
                    "minecraft:bundle", "minecraft:leather_horse_armor")),
            Map.entry("shepherd", List.of("minecraft:loom", "minecraft:painting", "minecraft:white_bed",
                    "minecraft:white_carpet")),
            Map.entry("cartographer", List.of("minecraft:cartography_table", "minecraft:map", "minecraft:compass",
                    "minecraft:clock")),
            Map.entry("mason", List.of("minecraft:stonecutter", "minecraft:stone_bricks", "minecraft:polished_andesite",
                    "minecraft:chiseled_stone_bricks")),
            Map.entry("fletcher", List.of("minecraft:fletching_table", "minecraft:bow", "minecraft:arrow",
                    "minecraft:target")),
            Map.entry("none", List.of("minecraft:campfire", "minecraft:lantern", "minecraft:chest")));

    private AiSkills() {
    }

    /** Skill level 0..5 from practice. Pure. */
    static int level(int xp) {
        int level = 0;
        for (int threshold : THRESHOLDS) {
            if (xp >= threshold) {
                level++;
            }
        }
        return level;
    }

    static String words(int level) {
        return switch (level) {
            case 0 -> "a beginner";
            case 1 -> "learning";
            case 2 -> "decent";
            case 3 -> "skilled";
            case 4 -> "very skilled";
            default -> "a master";
        };
    }

    /** How much faster a skilled villager does the hands-on part of a task (0.6 .. 1.0). Pure. */
    static double speed(int level) {
        return 1.0 - 0.08 * Math.max(0, Math.min(5, level));
    }

    static int level(MinecraftServer server, java.util.UUID villager, AiChore chore) {
        return level(AiLivesSavedData.get(server).skillXp(villager, chore.key()));
    }

    /** Items brought back from a task are practice. */
    static void practice(MinecraftServer server, Entity villager, AiChore chore, int items) {
        AiLivesSavedData lives = AiLivesSavedData.get(server);
        int before = level(lives.skillXp(villager.getUUID(), chore.key()));
        lives.addSkillXp(villager.getUUID(), chore.key(), Math.max(0, items));
        int after = level(lives.skillXp(villager.getUUID(), chore.key()));
        if (after > before && McaConversationsConfig.debugAi()) {
            dev.otectus.mcaconversations.McaConversations.LOGGER.info("[ai] {} is now {} at {}",
                    villager.getUUID(), words(after), chore.key());
        }
    }

    /** The player showed the villager how to do a task better. */
    static boolean teach(MinecraftServer server, Entity villager, ServerPlayer player, String villagerName, AiChore chore,
                         long day) {
        AiLivesSavedData lives = AiLivesSavedData.get(server);
        if (!lives.claimTeaching(villager.getUUID(), chore.key(), day)) {
            return false;
        }
        lives.addSkillXp(villager.getUUID(), chore.key(), TEACHING_XP);
        AiMemorySavedData.get(server).edit(villager.getUUID(), player.getUUID()).remember(new AiMemoryNote(
                player.getName().getString() + " showed me how to get better at " + chore.key() + ".",
                AiImportance.MEDIUM), AiSentiment.POSITIVE, day, Math.max(1, McaConversationsConfig.aiMemoriesPerPair()));
        player.displayClientMessage(Component.translatable("mcaconversations.ai.skill.taught", villagerName,
                Component.translatable("mcaconversations.ai.chore." + chore.key())).withStyle(ChatFormatting.AQUA), true);
        return true;
    }

    static List<String> promptLines(MinecraftServer server, Entity villager, String playerName) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> skills = AiLivesSavedData.get(server).skills(villager.getUUID());
        List<String> known = new ArrayList<>();
        skills.forEach((chore, xp) -> known.add(chore + " (" + words(level(xp)) + ")"));
        if (!known.isEmpty()) {
            out.add("What you have learned to do: " + String.join(", ", known) + ".");
        }
        return out;
    }

    // --- the villager teaching the player --------------------------------------------------------------------

    private static String trade(Entity villager) {
        String id = McaCompat.getProfessionId(villager).orElse("none").toLowerCase(Locale.ROOT);
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return TRADE_RECIPES.containsKey(path) ? path : "none";
    }

    /** Recipes of this villager's trade the player does not know yet, as item ids. */
    static Set<String> teachable(Entity villager, ServerPlayer player) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : TRADE_RECIPES.get(trade(villager))) {
            if (out.size() >= MAX_RECIPES_OFFERED) {
                break;
            }
            List<RecipeHolder<?>> recipes = recipesFor(player, id);
            if (!recipes.isEmpty() && recipes.stream().noneMatch(r -> player.getRecipeBook().contains(r))) {
                out.add(id);
            }
        }
        return out;
    }

    private static List<RecipeHolder<?>> recipesFor(ServerPlayer player, String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return List.of();
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        var access = player.serverLevel().registryAccess();
        List<RecipeHolder<?>> out = new ArrayList<>();
        for (RecipeHolder<?> holder : player.serverLevel().getRecipeManager().getRecipes()) {
            try {
                if (holder.value().getResultItem(access).is(item)) {
                    out.add(holder);
                }
            } catch (Throwable ignored) {
                // a recipe that cannot answer is not one to teach
            }
        }
        return out;
    }

    /** The villager shows the player how to make something; the recipe book learns it. */
    static boolean teachRecipe(MinecraftServer server, Entity villager, ServerPlayer player, String villagerName,
                               String itemId, long day) {
        List<RecipeHolder<?>> recipes = recipesFor(player, itemId);
        if (recipes.isEmpty() || player.awardRecipes(recipes) == 0) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
        AiMemorySavedData.get(server).edit(villager.getUUID(), player.getUUID()).remember(new AiMemoryNote(
                "I taught " + player.getName().getString() + " how to make " + AiContextFormat.words(itemId) + ".",
                AiImportance.LOW), AiSentiment.POSITIVE, day, Math.max(1, McaConversationsConfig.aiMemoriesPerPair()));
        player.displayClientMessage(Component.translatable("mcaconversations.ai.recipe.taught", villagerName,
                Component.translatable(item.getDescriptionId())).withStyle(ChatFormatting.AQUA), false);
        return true;
    }
}
