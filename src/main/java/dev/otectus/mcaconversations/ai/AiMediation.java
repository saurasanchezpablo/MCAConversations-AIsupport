package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.conversation.DepthClass;
import dev.otectus.mcaconversations.progress.ReplayPolicy;
import dev.otectus.mcaconversations.state.ConversationState;
import dev.otectus.mcaconversations.state.StateTracker;
import dev.otectus.mcaconversations.voice.VoiceIntent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Making peace between two neighbours who have fallen out. A player talks one of them round (the
 * model's {@code reconcile} effect, allowed only for a feud the villager was shown); that villager
 * lets go of the worst of it and is ready to make peace. When the other is ready too (or never held
 * it against them) the two are reconciled: their grievances are dropped, they meet and make up if
 * they are near, both remember who brought them together and are grateful to the player for it.
 */
final class AiMediation {

    /** Hearts with each of the two when they are reconciled (once per pair and day). */
    static final int PEACE_HEARTS = 2;
    static final String[] AXES = {"warmth", "trust", "respect"};

    private AiMediation() {
    }

    /** Whether this opinion is a grievance: more bad than good. Pure. */
    static boolean feud(AiNeighbourOpinion opinion) {
        return opinion.warmth() + opinion.trust() + opinion.respect() < 0;
    }

    /** The neighbours (by exact name, as shown) this villager has fallen out with. */
    static List<String> feuds(AiMemorySavedData data, UUID villager, Map<String, UUID> neighbours) {
        List<String> out = new ArrayList<>();
        for (AiNeighbourOpinion opinion : data.opinions(villager)) {
            if (feud(opinion)) {
                neighbours.entrySet().stream().filter(e -> e.getValue().equals(opinion.target())).findFirst()
                        .ifPresent(e -> out.add(e.getKey()));
            }
        }
        return out;
    }

    /** Prompt lines: neighbours who sent word, through a player, that they want to make peace. */
    static List<String> promptLines(MinecraftServer server, UUID villager, long day) {
        List<String> out = new ArrayList<>();
        for (AiVillageLifeSavedData.PeaceOffer offer : AiVillageLifeSavedData.get(server).offersTo(villager, day)) {
            ServerPlayer carrier = server.getPlayerList().getPlayer(offer.player());
            out.add("Through " + (carrier == null ? "a traveller" : carrier.getName().getString()) + ", " + offer.fromName()
                    + " has said they want to make peace with you. You may accept if you are persuaded.");
        }
        return out;
    }

    /**
     * The villager agreed to make peace with {@code withName}. Softens their side; if the other side is
     * ready too (or bears no grudge), reconciles them. Returns whether anything changed.
     */
    static boolean reconcile(MinecraftServer server, Entity villager, ServerPlayer player, String villagerName,
                             String withName, AiSocial.Turn turn, long now, long day) {
        UUID self = villager.getUUID();
        Map.Entry<String, UUID> target = turn.neighbourIds().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(withName)).findFirst().orElse(null);
        if (target == null || target.getValue().equals(self)) {
            return false;
        }
        UUID other = target.getValue();
        String otherName = target.getKey();
        AiMemorySavedData memory = AiMemorySavedData.get(server);
        AiVillageLifeSavedData life = AiVillageLifeSavedData.get(server);
        String playerName = player.getName().getString();
        Optional<AiNeighbourOpinion> theirs = memory.opinions(other).stream().filter(o -> o.target().equals(self)).findFirst();
        boolean theyFeud = theirs.map(AiMediation::feud).orElse(false);
        boolean theyOffered = life.peaceOffer(other, self, day).isPresent();
        if (theyFeud && !theyOffered) {
            // Half of it: this one is ready; the other still has to be talked round.
            soften(memory, self, other, otherName, 1, playerName + " talked me into making peace", day);
            life.offerPeace(new AiVillageLifeSavedData.PeaceOffer(self, other, villagerName, player.getUUID(), day));
            player.displayClientMessage(Component.translatable("mcaconversations.ai.mediation.half", villagerName, otherName)
                    .withStyle(ChatFormatting.AQUA), true);
            return true;
        }
        // Both ready: grievances dropped on both sides, and a little warmth back.
        String cause = "we made peace, thanks to " + playerName;
        soften(memory, self, other, otherName, AiNeighbourOpinion.LIMIT * 2, cause, day);
        soften(memory, other, self, villagerName, AiNeighbourOpinion.LIMIT * 2, cause, day);
        memory.adjustOpinion(self, other, otherName, "warmth", 1, cause, day);
        memory.adjustOpinion(other, self, villagerName, "warmth", 1, cause, day);
        life.clearPeace(self, other);
        int cap = McaConversationsConfig.aiMemoriesPerPair();
        if (cap > 0) {
            memory.edit(self, player.getUUID()).remember(new AiMemoryNote(playerName + " helped me make peace with "
                    + otherName + ".", AiImportance.HIGH), AiSentiment.STRONGLY_POSITIVE, day, cap);
            memory.edit(other, player.getUUID()).remember(new AiMemoryNote(playerName + " helped me make peace with "
                    + villagerName + ".", AiImportance.HIGH), AiSentiment.STRONGLY_POSITIVE, day, cap);
        }
        String pair = self.compareTo(other) < 0 ? self + "-" + other : other + "-" + self;
        Entity otherEntity = player.serverLevel().getEntity(other);
        if (McaConversationsConfig.aiRelationshipEffects()) {
            AiHearts.grant(server, villager, player, "ai.mediation", PEACE_HEARTS, DepthClass.STANDARD,
                    ReplayPolicy.ONCE_PER_DAY, 0, 0, "ai.mediation." + pair + "." + day + "." + self, now);
            if (otherEntity != null) {
                AiHearts.grant(server, otherEntity, player, "ai.mediation", PEACE_HEARTS, DepthClass.STANDARD,
                        ReplayPolicy.ONCE_PER_DAY, 0, 0, "ai.mediation." + pair + "." + day + "." + other, now);
            }
        }
        StateTracker.apply(villager, player, ConversationState.GRATEFUL);
        if (otherEntity != null) {
            StateTracker.apply(otherEntity, player, ConversationState.GRATEFUL);
        }
        player.displayClientMessage(Component.translatable("mcaconversations.ai.mediation.done", villagerName, otherName)
                .withStyle(ChatFormatting.GREEN), true);
        meet(villager, otherEntity, player, villagerName, otherName, now);
        return true;
    }

    /** Moves each negative axis of {@code from}'s view of {@code to} up by at most {@code step}, never past 0. */
    private static void soften(AiMemorySavedData memory, UUID from, UUID to, String toName, int step, String cause, long day) {
        Optional<AiNeighbourOpinion> opinion = memory.opinions(from).stream().filter(o -> o.target().equals(to)).findFirst();
        if (opinion.isEmpty()) {
            return;
        }
        for (String axis : AXES) {
            int value = opinion.get().value(axis);
            if (value < 0) {
                memory.adjustOpinion(from, to, toName, axis, Math.min(step, -value), cause, day);
            }
        }
    }

    /** If the two are near each other, they walk over, and each says a word of peace. */
    private static void meet(Entity villager, Entity other, ServerPlayer player, String villagerName, String otherName,
                             long now) {
        if (other == null || !other.isAlive() || other.distanceTo(villager) > 24) {
            return;
        }
        if (other instanceof Mob mob) {
            mob.getNavigation().moveTo(villager, 0.6);
        }
        AiLines.sayLater(villager, player, AiLines.variant("mediation.made_up", otherName), villagerName, now, 60,
                AiEmotion.HAPPY, VoiceIntent.STATEMENT);
        AiLines.sayLater(other, player, AiLines.variant("mediation.made_up", villagerName),
                McaCompat.getVillagerName(other).orElse(otherName), now, 120, AiEmotion.HAPPY, VoiceIntent.STATEMENT);
    }
}
