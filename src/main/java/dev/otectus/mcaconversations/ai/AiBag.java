package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import dev.otectus.mcaconversations.conversation.DepthClass;
import dev.otectus.mcaconversations.conversation.RelationshipBand;
import dev.otectus.mcaconversations.conversation.Relationships;
import dev.otectus.mcaconversations.progress.AffectionMath;
import dev.otectus.mcaconversations.progress.ReplayPolicy;
import dev.otectus.mcaconversations.state.ConversationState;
import dev.otectus.mcaconversations.state.StateTracker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The villager's bag. When a player opens a villager's inventory (MCA's own screen), the game notes
 * what was in it, and when the screen closes it sees what the player took or put in. The villager then
 * reacts like a person would.
 *
 * <ul>
 *   <li>Taking back what you lent them: fine, it is yours. A tool they were working with means the work
 *       stops, and they say so.</li>
 *   <li>Taking what they gathered for you: fine, it was for you.</li>
 *   <li>Taking their own things: it depends who you are to them and what it was worth. Family or a
 *       spouse hardly mind; a friend is put out by something valuable; a near stranger calls it what
 *       it is, theft, and holds a grudge.</li>
 *   <li>Putting a tool in: a loan (taking it back later is fine), and if they were waiting for it, they
 *       get to work. Something useful: a kind gesture. Junk: they are puzzled.</li>
 * </ul>
 *
 * <p>The consequences (hearts, a memory, a grudge) are the game's, decided here. The model only voices
 * the reaction and is told how the villager feels, so words and consequences agree.
 */
final class AiBag {

    /** How the villager takes having their own things taken. */
    enum Verdict { FINE, MILD, UPSET, THEFT }

    /** One kind of item that moved, with how many and what it is worth to a villager. */
    record Moved(String id, String words, int count, int value, boolean tool) {
    }

    private record Snapshot(UUID villager, Map<String, ItemStack> items) {
    }

    private static final Map<UUID, Snapshot> OPEN = new HashMap<>();

    /** Junk nobody wants slipped into their bag. */
    static final Set<String> JUNK = Set.of("minecraft:dirt", "minecraft:rotten_flesh", "minecraft:poisonous_potato",
            "minecraft:spider_eye", "minecraft:gravel", "minecraft:dead_bush", "minecraft:string", "minecraft:bone",
            "minecraft:netherrack", "minecraft:cobbled_deepslate", "minecraft:tuff", "minecraft:diorite",
            "minecraft:granite", "minecraft:andesite");

    private AiBag() {
    }

    // --- pure rules ----------------------------------------------------------------------------------

    /** What some items are worth to a villager, roughly. Pure. */
    static int value(String id, int count, boolean enchanted, boolean damageable) {
        int each;
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (path.contains("netherite") || path.contains("diamond") || path.contains("emerald")) {
            each = path.endsWith("_block") ? 90 : 10;
        } else if (enchanted || path.contains("golden") || path.equals("gold_ingot") || path.equals("gold_block")) {
            each = path.endsWith("_block") ? 36 : 4;
        } else if (damageable || path.equals("iron_ingot") || path.endsWith("_ingot")) {
            each = 4;
        } else if (JUNK.contains(id)) {
            return 0;
        } else {
            return Math.max(1, count / 4);
        }
        return each * count;
    }

    /**
     * How a villager takes it when a player helps themselves to their things: by how close they are
     * and what it was worth. Pure.
     */
    static Verdict judge(RelationshipBand band, boolean close, int value) {
        if (value <= 0) {
            return Verdict.FINE;
        }
        if (close || band == RelationshipBand.CONFIDANT || band == RelationshipBand.PARTNER || band == RelationshipBand.FAMILY) {
            return value < 20 ? Verdict.FINE : Verdict.MILD;
        }
        if (band == RelationshipBand.FRIEND) {
            return value <= 5 ? Verdict.FINE : value <= 20 ? Verdict.MILD : Verdict.UPSET;
        }
        if (band == RelationshipBand.ACQUAINTANCE) {
            return value <= 2 ? Verdict.MILD : value <= 20 ? Verdict.UPSET : Verdict.THEFT;
        }
        return value <= 5 ? Verdict.UPSET : Verdict.THEFT;
    }

    static int hearts(Verdict verdict) {
        return switch (verdict) {
            case FINE -> 0;
            case MILD -> -1;
            case UPSET -> -3;
            case THEFT -> -5;
        };
    }

    /** How the villager feels about it, for the model. Pure. */
    static String feeling(Verdict verdict) {
        return switch (verdict) {
            case FINE -> "between the two of you that is fine, you do not mind at all";
            case MILD -> "you are a little put out, mostly because they did not ask";
            case UPSET -> "you are upset: those were yours and they just took them";
            case THEFT -> "you are furious: that is stealing, and you will not do them favours for a while";
        };
    }

    static String list(List<Moved> items) {
        List<String> parts = new ArrayList<>();
        for (Moved m : items) {
            parts.add(m.count() > 1 ? m.count() + " " + m.words()
                    : ("aeiou".indexOf(m.words().isEmpty() ? 'x' : m.words().charAt(0)) >= 0 ? "an " : "a ") + m.words());
        }
        return String.join(", ", parts);
    }

    // --- watching the screen -------------------------------------------------------------------------

    /** The villager whose own inventory this menu shows, if any (MCA's inventory screen). */
    private static Entity villagerOf(ServerPlayer player, AbstractContainerMenu menu) {
        Set<Container> containers = new java.util.HashSet<>();
        for (Slot slot : menu.slots) {
            if (slot.container != player.getInventory()) {
                containers.add(slot.container);
            }
        }
        if (containers.isEmpty()) {
            return null;
        }
        for (Entity e : player.serverLevel().getEntities(player, player.getBoundingBox().inflate(10),
                e -> e.isAlive() && McaCompat.isMcaVillager(e))) {
            Container inventory = McaHandles.inventory(e);
            if (inventory != null && containers.contains(inventory)) {
                return e;
            }
        }
        return null;
    }

    private static String key(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "#" + stack.getComponentsPatch().hashCode();
    }

    private static Map<String, ItemStack> contents(Container inventory) {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            out.merge(key(stack), stack.copy(), (a, b) -> {
                a.grow(b.getCount());
                return a;
            });
        }
        return out;
    }

    static void opened(ServerPlayer player, AbstractContainerMenu menu) {
        if (!AiConversations.enabled() || !McaConversationsConfig.aiGameplayEffects()) {
            return;
        }
        Entity villager = villagerOf(player, menu);
        Container inventory = villager == null ? null : McaHandles.inventory(villager);
        if (inventory != null) {
            OPEN.put(player.getUUID(), new Snapshot(villager.getUUID(), contents(inventory)));
        }
    }

    static void closed(ServerPlayer player) {
        Snapshot before = OPEN.remove(player.getUUID());
        MinecraftServer server = player.getServer();
        if (before == null || server == null) {
            return;
        }
        Entity villager = player.serverLevel().getEntity(before.villager());
        Container inventory = villager == null ? null : McaHandles.inventory(villager);
        if (inventory == null || !villager.isAlive()) {
            return;
        }
        try {
            react(server, villager, player, before.items(), contents(inventory));
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("bag check failed", t);
        }
    }

    private static Moved moved(ItemStack proto, int count) {
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(proto.getItem()));
        return new Moved(id, AiContextFormat.words(id), count,
                value(id, count, proto.isEnchanted(), proto.isDamageableItem()), AiWork.isTool(proto) || proto.isDamageableItem());
    }

    // --- reacting ----------------------------------------------------------------------------------------

    private static void react(MinecraftServer server, Entity villager, ServerPlayer player, Map<String, ItemStack> before,
                              Map<String, ItemStack> after) {
        UUID v = villager.getUUID();
        UUID p = player.getUUID();
        AiLivesSavedData lives = AiLivesSavedData.get(server);
        java.util.Optional<AiWork.Job> job = AiWork.job(v).filter(j -> j.player.equals(p));

        List<Moved> returned = new ArrayList<>();
        List<Moved> haul = new ArrayList<>();
        List<Moved> own = new ArrayList<>();
        for (Map.Entry<String, ItemStack> e : before.entrySet()) {
            int lost = e.getValue().getCount() - (after.containsKey(e.getKey()) ? after.get(e.getKey()).getCount() : 0);
            if (lost <= 0) {
                continue;
            }
            ItemStack proto = e.getValue();
            String id = String.valueOf(BuiltInRegistries.ITEM.getKey(proto.getItem()));
            int back = lives.returnLoan(v, p, id, lost);
            if (back > 0) {
                returned.add(moved(proto, back));
            }
            int rest = lost - back;
            if (rest > 0 && job.isPresent() && AiWork.yieldOf(job.get().chore).test(proto)) {
                haul.add(moved(proto, rest));
                rest = 0;
            }
            if (rest > 0) {
                own.add(moved(proto, rest));
            }
        }
        List<Moved> useful = new ArrayList<>();
        List<Moved> junk = new ArrayList<>();
        List<Moved> lent = new ArrayList<>();
        for (Map.Entry<String, ItemStack> e : after.entrySet()) {
            int gained = e.getValue().getCount() - (before.containsKey(e.getKey()) ? before.get(e.getKey()).getCount() : 0);
            if (gained <= 0) {
                continue;
            }
            Moved m = moved(e.getValue(), gained);
            if (job.isPresent() && AiWork.yieldOf(job.get().chore).test(e.getValue())) {
                continue; // what they gathered meanwhile, not something the player put in
            }
            if (m.tool()) {
                lives.addLoan(v, p, m.id(), gained);
                lent.add(m);
            } else if (JUNK.contains(m.id())) {
                junk.add(m);
            } else {
                useful.add(m);
            }
        }
        if (returned.isEmpty() && haul.isEmpty() && own.isEmpty() && useful.isEmpty() && junk.isEmpty() && lent.isEmpty()) {
            return;
        }

        String villagerName = McaCompat.getVillagerName(villager).orElse(villager.getName().getString());
        String playerName = player.getName().getString();
        RelationshipBand band;
        boolean close;
        try {
            band = Relationships.bandOf(villager, player);
            close = Relationships.rolesOf(villager, player).any() || McaCompat.isMarriedToPlayer(villager, p);
        } catch (Throwable t) {
            band = RelationshipBand.STRANGER;
            close = false;
        }
        long now = villager.level().getGameTime();
        long day = AffectionMath.dayOf(now);
        int cap = Math.max(1, McaConversationsConfig.aiMemoriesPerPair());
        AiMemorySavedData memory = AiMemorySavedData.get(server);
        List<String> what = new ArrayList<>();

        // Taken back what was lent; if it was the tool for the job, the job is over.
        boolean workStopped = false;
        if (!returned.isEmpty()) {
            what.add("took back the " + list(returned) + " they had lent you (that is fine, it is theirs)");
            if (job.isPresent() && !AiWork.hasTool(villager, job.get().chore)) {
                AiWork.stop(v, true);
                McaHandles.runInteraction(villager, player, "stopworking");
                workStopped = true;
                what.add("without it you cannot keep working, so you have stopped");
            }
        }
        if (!haul.isEmpty()) {
            what.add("took the " + list(haul) + " you had gathered for them (that is fine, it was for them)");
        }
        Verdict verdict = null;
        if (!own.isEmpty()) {
            int worth = own.stream().mapToInt(Moved::value).sum();
            verdict = judge(band, close, worth);
            what.add("took your own " + list(own) + " without asking; " + feeling(verdict));
            int delta = hearts(verdict);
            if (delta != 0 && McaConversationsConfig.aiRelationshipEffects()) {
                AiHearts.grant(server, villager, player, "ai.bag.taken", delta, DepthClass.STANDARD, ReplayPolicy.ONCE,
                        0, 0, "ai.bag.taken." + v + "." + now, now);
            }
            AiPairMemory pair = memory.edit(v, p);
            switch (verdict) {
                case FINE -> pair.remember(new AiMemoryNote(playerName + " took " + list(own)
                        + " from my bag. Between us, that is fine.", AiImportance.LOW), AiSentiment.NEUTRAL, day, cap);
                case MILD -> pair.remember(new AiMemoryNote(playerName + " took " + list(own)
                        + " from my bag without asking.", AiImportance.LOW), AiSentiment.NEGATIVE, day, cap);
                case UPSET -> {
                    pair.remember(new AiMemoryNote(playerName + " went through my bag and took " + list(own) + ".",
                            AiImportance.MEDIUM), AiSentiment.NEGATIVE, day, cap);
                    StateTracker.apply(villager, player, ConversationState.ANNOYED);
                }
                case THEFT -> {
                    pair.remember(new AiMemoryNote(playerName + " stole " + list(own) + " from me.", AiImportance.HIGH),
                            AiSentiment.STRONGLY_NEGATIVE, day, cap);
                    pair.holdGrudge(now + AiSocialEffects.GRUDGE_TICKS, playerName + " stole " + list(own) + " from me");
                    StateTracker.apply(villager, player, ConversationState.ANNOYED);
                }
            }
        }
        if (!lent.isEmpty() || !useful.isEmpty() || !junk.isEmpty()) {
            AiConversations.markReceived(v, p, now);
        }
        if (!lent.isEmpty()) {
            boolean started = AiWork.resumeIfReady(villager, player, villagerName, now);
            what.add("put a " + list(lent) + " in your bag, lent to you" + (started
                    ? ", the very tool you needed, so you are getting to work now" : ""));
        }
        if (!useful.isEmpty()) {
            what.add("slipped " + list(useful) + " into your bag, which is kind of them");
            if (verdict == null || verdict == Verdict.FINE) {
                if (McaConversationsConfig.aiRelationshipEffects()) {
                    AiHearts.grant(server, villager, player, "ai.bag.given", 1, DepthClass.STANDARD, ReplayPolicy.ONCE,
                            0, 0, "ai.bag.given." + v + "." + day, now);
                }
                memory.edit(v, p).remember(new AiMemoryNote(playerName + " slipped " + list(useful) + " into my bag.",
                        AiImportance.LOW), AiSentiment.POSITIVE, day, cap);
            }
        }
        if (!junk.isEmpty()) {
            what.add("stuffed " + list(junk) + " into your bag, which is just junk; you are puzzled, or a bit annoyed");
            memory.edit(v, p).remember(new AiMemoryNote(playerName + " stuffed " + list(junk) + " into my bag. Why?",
                    AiImportance.LOW), AiSentiment.NEUTRAL, day, cap);
        }
        if (McaConversationsConfig.debugAi()) {
            McaConversations.LOGGER.info("[ai] bag villager={} verdict={} {}", v, verdict, what);
        }
        String reason = playerName + " just went into your bag and " + String.join("; and ", what)
                + ". React to it now, in character, in one or two sentences, feeling exactly as described.";
        AiConversations.open(player, villager, reason);
    }

    /** What the villager holds that the player lent them, for the prompt. */
    static List<String> promptLines(MinecraftServer server, Entity villager, ServerPlayer player) {
        Map<String, Integer> lent = AiLivesSavedData.get(server).loans(villager.getUUID(), player.getUUID());
        if (lent.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        lent.forEach((id, n) -> parts.add((n > 1 ? n + " " : "a ") + AiContextFormat.words(id)));
        return List.of("You are holding " + String.join(", ", parts) + " that " + player.getName().getString()
                + " lent you. It is theirs; they can take it back whenever they like.");
    }

    static void forget(UUID player) {
        OPEN.remove(player);
    }

    static void reset() {
        OPEN.clear();
    }
}
