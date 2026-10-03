package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import dev.otectus.mcaconversations.conversation.AgeGroup;
import dev.otectus.mcaconversations.conversation.RelationshipBand;
import dev.otectus.mcaconversations.conversation.Relationships;
import dev.otectus.mcaconversations.gossip.GossipEvent;
import dev.otectus.mcaconversations.gossip.GossipSavedData;
import dev.otectus.mcaconversations.progress.AffectionMath;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Villagers starting AI conversations on their own: one comes over and opens with something that is
 * actually on their mind about this player (a promise falling due, a promise kept, a loss, what the
 * village is saying, not having seen them for days, someone they love) or, now and then, small talk.
 *
 * <p>Kept rare and polite: at most one opening per player per cooldown, the same villager at most
 * every {@link #PAIR_COOLDOWN_TICKS}, never while the player is already talking to someone, in a
 * menu, or the villager is asleep, panicking, busy with another player or holding a grudge. The
 * stronger the reason, the likelier the villager is to come over.
 */
final class AiInitiative {

    static final int CHECK_INTERVAL_TICKS = 200;
    static final long PAIR_COOLDOWN_TICKS = 12_000;

    /** Why a villager would come over, and how much it matters (1 small talk .. 5 pressing). */
    record Reason(int weight, String text) {
    }

    /** What the villager knows about this player that could be worth walking over for. Pure input. */
    record Facts(String playerName, RelationshipBand band, boolean romantic, int turns, long daysSinceTalk,
                 Optional<String> promiseDue, Optional<String> promiseKept, Optional<String> loss,
                 Optional<String> heardAbout, Optional<String> wish) {
    }

    private static final Map<UUID, Long> LAST_BY_PLAYER = new HashMap<>();
    private static final Map<String, Long> LAST_BY_PAIR = new HashMap<>();

    private AiInitiative() {
    }

    /** The strongest reason this villager has to start talking, if any. Pure. */
    static Optional<Reason> reason(Facts f) {
        List<Reason> reasons = new ArrayList<>();
        String p = f.playerName();
        f.promiseDue().ifPresent(s -> reasons.add(new Reason(5, p + " promised you " + s + " and it is due now")));
        f.promiseKept().ifPresent(s -> reasons.add(new Reason(4, p + " just kept a promise to you (" + s
                + ") and you want to thank them")));
        if (f.band().isAtLeast(RelationshipBand.ACQUAINTANCE)) {
            f.loss().ifPresent(s -> reasons.add(new Reason(4, "you are grieving " + s + " and want someone to talk to")));
        }
        f.heardAbout().ifPresent(s -> reasons.add(new Reason(3, "you heard that " + s)));
        if (f.romantic()) {
            reasons.add(new Reason(3, "you are happy to see the one you love"));
        }
        if (f.band().isAtLeast(RelationshipBand.FRIEND) && f.daysSinceTalk() >= 3) {
            reasons.add(new Reason(3, "you have not seen " + p + " for " + f.daysSinceTalk() + " days"));
        }
        if (f.band() == RelationshipBand.HOSTILE || f.band() == RelationshipBand.TENSE) {
            return Optional.empty(); // a villager who dislikes the player does not seek them out
        }
        f.wish().ifPresent(s -> reasons.add(new Reason(2, "you are still hoping someone brings you " + s)));
        if (f.turns() == 0 && f.band() == RelationshipBand.STRANGER) {
            reasons.add(new Reason(1, p + " is a stranger you have never spoken to and you are curious about them"));
        } else {
            reasons.add(new Reason(1, "you feel like chatting about your day"));
        }
        return reasons.stream().max(Comparator.comparingInt(Reason::weight));
    }

    /** The chance a villager with this reason comes over on one check. Pure. */
    static double chance(Reason reason, double base) {
        return Math.min(1.0, base * reason.weight());
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        long now = server.overworld().getGameTime();
        long cooldown = McaConversationsConfig.aiAutoConversationCooldownTicks();
        double base = McaConversationsConfig.aiAutoConversationChance();
        double radius = McaConversationsConfig.aiAutoConversationRadius();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!eligible(player, now, cooldown)) {
                continue;
            }
            Optional<Entity> chosen = Optional.empty();
            Reason best = null;
            for (Entity villager : player.serverLevel().getEntities(player, player.getBoundingBox().inflate(radius),
                    e -> e.isAlive() && McaCompat.isMcaVillager(e))) {
                if (!available(villager, player, now)) {
                    continue;
                }
                Optional<Reason> reason = reason(facts(server, villager, player, now));
                if (reason.isPresent() && (best == null || reason.get().weight() > best.weight())) {
                    best = reason.get();
                    chosen = Optional.of(villager);
                }
            }
            if (chosen.isEmpty() || ThreadLocalRandom.current().nextDouble() >= chance(best, base)) {
                continue;
            }
            Entity villager = chosen.get();
            LAST_BY_PLAYER.put(player.getUUID(), now);
            LAST_BY_PAIR.put(pairKey(villager.getUUID(), player.getUUID()), now);
            AiConversations.open(player, villager, best.text());
        }
    }

    private static boolean eligible(ServerPlayer player, long now, long cooldown) {
        if (player.isSpectator() || !player.isAlive() || player.isSleeping() || player.containerMenu != player.inventoryMenu) {
            return false;
        }
        Long last = LAST_BY_PLAYER.get(player.getUUID());
        if (last != null && now - last < cooldown) {
            return false;
        }
        // Already talking with someone: do not interrupt.
        return AiConversations.partner(player.getUUID(), now).isEmpty();
    }

    private static boolean available(Entity villager, ServerPlayer player, long now) {
        AgeGroup age = McaCompat.ageGroup(villager);
        if (age == AgeGroup.BABY || age == AgeGroup.TODDLER || age == AgeGroup.UNKNOWN || McaHandles.silentVoice(villager)) {
            return false;
        }
        if (villager instanceof LivingEntity living && (living.isSleeping() || !living.hasLineOfSight(player))) {
            return false;
        }
        if (McaCompat.isPanicking(villager)
                || McaCompat.isInteractingWith(villager).map(id -> !id.equals(player.getUUID())).orElse(false)) {
            return false;
        }
        Long last = LAST_BY_PAIR.get(pairKey(villager.getUUID(), player.getUUID()));
        if (last != null && now - last < PAIR_COOLDOWN_TICKS) {
            return false;
        }
        return AiMemorySavedData.get(player.getServer()).peek(villager.getUUID(), player.getUUID())
                .map(pair -> !pair.grudge(now)).orElse(true);
    }

    private static Facts facts(MinecraftServer server, Entity villager, ServerPlayer player, long now) {
        long day = AffectionMath.dayOf(now);
        AiMemorySavedData data = AiMemorySavedData.get(server);
        AiPairMemory pair = data.peek(villager.getUUID(), player.getUUID()).orElse(new AiPairMemory());
        RelationshipBand band;
        try {
            band = Relationships.bandOf(villager, player);
        } catch (Throwable t) {
            band = RelationshipBand.STRANGER;
        }
        boolean romantic = McaCompat.isMarriedToPlayer(villager, player.getUUID())
                || McaHandles.isEngagedWith(villager, player.getUUID()) || McaHandles.isPromisedTo(villager, player.getUUID());
        Optional<String> due = pair.promises().stream().filter(p -> p.pending() && day >= p.dueDay())
                .map(AiPromises::describe).findFirst();
        Optional<String> kept = pair.promises().stream()
                .filter(p -> p.state() == AiPromise.State.KEPT && day - p.settledDay() <= 1).map(AiPromises::describe).findFirst();
        Optional<String> loss = data.bereavements(villager.getUUID(), day).stream().findFirst()
                .map(b -> "your " + b.relation() + " " + b.name());
        long daysSince = pair.lastTalkDay() < 0 ? 0 : day - pair.lastTalkDay();
        return new Facts(player.getName().getString(), band, romantic, pair.turns(), daysSince, due, kept, loss,
                heardAbout(server, villager, player, now), pair.wish(day).map(w -> AiContextFormat.words(w.item().replace("#", ""))));
    }

    private static Optional<String> heardAbout(MinecraftServer server, Entity villager, ServerPlayer player, long now) {
        OptionalInt village = McaCompat.getHomeVillageId(villager);
        if (village.isEmpty()) {
            return Optional.empty();
        }
        return GossipSavedData.get(server).log().events().stream()
                .filter(e -> e.villageId() == village.getAsInt() && e.type().aboutListener()
                        && player.getUUID().equals(e.aUuid()) && !e.involves(villager.getUUID())
                        && now - e.created() <= 48_000)
                .max(Comparator.comparingLong(GossipEvent::created))
                .map(e -> e.aName() + (e.type().name().endsWith("KINDNESS") ? " was kind to " : " was cruel to ") + e.bName());
    }

    private static String pairKey(UUID villager, UUID player) {
        return villager + "/" + player;
    }

    static void reset() {
        LAST_BY_PLAYER.clear();
        LAST_BY_PAIR.clear();
    }
}
