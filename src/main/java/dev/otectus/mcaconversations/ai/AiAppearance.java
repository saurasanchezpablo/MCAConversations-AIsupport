package dev.otectus.mcaconversations.ai;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.TridentItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a villager can see of the player right now: their armour, what they hold, wounds, fire, rain,
 * hunger, sleepless nights, the horse they ride, the dog at their side, bulging pockets. These are
 * observations for the model to remark on the way a person would, now and then and only when it
 * fits, never as a list.
 */
final class AiAppearance {

    /** Ticks without sleep after which a player looks exhausted (three days). */
    static final int SLEEPLESS = 72_000;

    /** A snapshot of how the player looks. Pure data. */
    record Look(List<String> armour, boolean enchantedArmour, boolean pumpkin, String held, boolean weapon,
                float health, float maxHealth, boolean onFire, boolean wet, int food, int sinceRest, String mount,
                List<String> pets, int valuables, int xpLevel, boolean night, boolean invisible) {
    }

    private AiAppearance() {
    }

    static Look of(ServerPlayer player) {
        List<String> armour = new ArrayList<>();
        boolean enchanted = false;
        boolean pumpkin = false;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET}) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(Items.CARVED_PUMPKIN)) {
                pumpkin = true;
                continue;
            }
            armour.add(id(stack));
            enchanted |= stack.isEnchanted();
        }
        ItemStack hand = player.getMainHandItem();
        boolean weapon = hand.getItem() instanceof SwordItem || hand.getItem() instanceof AxeItem
                || hand.getItem() instanceof BowItem || hand.getItem() instanceof CrossbowItem
                || hand.getItem() instanceof TridentItem;
        int valuables = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.DIAMOND) || stack.is(Items.EMERALD) || stack.is(Items.GOLD_INGOT)
                    || stack.is(Items.NETHERITE_INGOT) || stack.is(Items.DIAMOND_BLOCK) || stack.is(Items.EMERALD_BLOCK)) {
                valuables += stack.getCount();
            }
        }
        List<String> pets = new ArrayList<>();
        for (Entity e : player.serverLevel().getEntities(player, player.getBoundingBox().inflate(8),
                e -> e instanceof TamableAnimal t && t.isTame() && player.getUUID().equals(t.getOwnerUUID()))) {
            if (pets.size() < 3) {
                pets.add(AiContextFormat.words(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()));
            }
        }
        Entity vehicle = player.getVehicle();
        int sinceRest = player.getStats().getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
        long time = player.serverLevel().getDayTime() % 24_000;
        return new Look(armour, enchanted, pumpkin, hand.isEmpty() ? "" : id(hand), weapon, player.getHealth(),
                player.getMaxHealth(), player.isOnFire(), player.isInWaterRainOrBubble(), player.getFoodData().getFoodLevel(),
                sinceRest, vehicle == null ? "" : AiContextFormat.words(BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).getPath()),
                pets, valuables, player.experienceLevel, time >= 13_000 && time < 23_000, player.isInvisible());
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    /** The material most of the armour is made of ("diamond"), or empty. Pure. */
    static String armourMaterial(List<String> armour) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (String piece : armour) {
            int cut = piece.lastIndexOf('_');
            if (cut > 0) {
                counts.merge(piece.substring(0, cut), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().filter(e -> e.getValue() >= 2).max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElse("");
    }

    /** Observations, in plain words. Pure. */
    static List<String> lines(Look l, String name) {
        List<String> out = new ArrayList<>();
        if (l.invisible()) {
            out.add("You cannot see " + name + " at all: they are invisible, only their voice is there. Unsettling.");
            return out;
        }
        String material = armourMaterial(l.armour());
        if (!material.isEmpty()) {
            out.add(name + " is wearing " + material.replace('_', ' ') + " armour"
                    + (l.enchantedArmour() ? ", and it shimmers with enchantments" : "")
                    + (material.equals("netherite") || material.equals("diamond") ? " (a fortune on their back)" : ""));
        } else if (l.armour().size() == 1) {
            out.add(name + " wears a " + l.armour().get(0).replace('_', ' '));
        }
        if (l.pumpkin()) {
            out.add(name + " has a carved pumpkin over their head. Odd.");
        }
        if (!l.held().isEmpty()) {
            out.add(name + (l.weapon() ? " has a " + l.held().replace('_', ' ') + " in hand, ready to use"
                    : " is holding a " + l.held().replace('_', ' ')));
        }
        float ratio = l.maxHealth() <= 0 ? 1f : l.health() / l.maxHealth();
        if (ratio <= 0.3f) {
            out.add(name + " is badly hurt, bleeding, barely on their feet");
        } else if (ratio <= 0.6f) {
            out.add(name + " looks hurt");
        }
        if (l.onFire()) {
            out.add(name + " is ON FIRE");
        } else if (l.wet()) {
            out.add(name + " is soaking wet");
        }
        if (l.food() <= 6) {
            out.add(name + " looks starving");
        }
        if (l.sinceRest() >= SLEEPLESS) {
            out.add(name + " looks exhausted, as if they have not slept for days");
        }
        if (!l.mount().isEmpty()) {
            out.add(name + " is riding a " + l.mount());
        }
        if (!l.pets().isEmpty()) {
            out.add(name + "'s " + String.join(" and ", l.pets()) + (l.pets().size() > 1 ? " are" : " is") + " with them");
        }
        if (l.valuables() >= 16) {
            out.add(name + "'s pockets are clinking with gems and gold");
        }
        if (l.xpLevel() >= 30) {
            out.add(name + " has the look of someone who has seen and learned a great deal");
        }
        if (l.night()) {
            out.add("It is night and " + name + " is out and about");
        }
        if (!out.isEmpty()) {
            out.add("Notice these as a person would: remark on one only when it is natural, never list them.");
        }
        return out;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
