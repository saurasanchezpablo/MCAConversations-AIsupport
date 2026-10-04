package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.conversation.DepthClass;
import dev.otectus.mcaconversations.progress.AffectionMath;
import dev.otectus.mcaconversations.progress.ReplayPolicy;
import dev.otectus.mcaconversations.state.ConversationState;
import dev.otectus.mcaconversations.state.StateTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Secrets and betrayal. A villager who trusts a player may confide in them (a memory marked secret).
 * If the player repeats it to someone else, and the listener's reply says so ({@code secret_told}),
 * the game checks that the neighbour named really did confide in this player. If so, the listener
 * remembers what they heard. Some time later, as rumours do, the one who confided finds out: they feel
 * betrayed, they remember it, they will not do the player favours for a while, and they think a good
 * deal less of them.
 */
final class AiSecrets {

    /** Earliest and latest the betrayed villager finds out, in ticks. */
    static final long FIND_OUT_MIN = 2_400;
    static final long FIND_OUT_SPREAD = 6_000;
    static final int BETRAYAL_HEARTS = -4;

    private AiSecrets() {
    }

    /** The player passed on {@code about}'s secret to {@code listener}. False when there was no secret to tell. */
    static boolean told(MinecraftServer server, Entity listener, ServerPlayer player, String listenerName, String about,
                        String summary, AiSocial.Turn turn, long now, long day) {
        UUID owner = turn.neighbourIds().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(about))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        if (owner == null || owner.equals(listener.getUUID())) {
            return false;
        }
        AiMemorySavedData memory = AiMemorySavedData.get(server);
        List<AiMemory> secrets = memory.peek(owner, player.getUUID()).map(AiPairMemory::secrets).orElse(List.of());
        if (secrets.isEmpty()) {
            return false; // nothing was confided: the player is making things up, which is another matter
        }
        String what = summary.isEmpty() ? secrets.get(secrets.size() - 1).text() : summary;
        AiLivesSavedData.get(server).addBetrayal(new AiLivesSavedData.Betrayal(owner, player.getUUID(), listenerName,
                what, now + FIND_OUT_MIN + ThreadLocalRandom.current().nextLong(FIND_OUT_SPREAD)));
        memory.edit(listener.getUUID(), player.getUUID()).remember(new AiMemoryNote(player.getName().getString()
                        + " told me something " + about + " had told them in confidence: " + what, AiImportance.LOW),
                AiSentiment.NEUTRAL, day, Math.max(1, McaConversationsConfig.aiMemoriesPerPair()));
        return true;
    }

    /** Rumours travel: betrayals that have come due are found out. */
    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 100 != 0) {
            return;
        }
        AiLivesSavedData lives = AiLivesSavedData.get(server);
        if (lives.betrayals().isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (AiLivesSavedData.Betrayal betrayal : List.copyOf(lives.betrayals())) {
            if (now < betrayal.due()) {
                continue;
            }
            lives.betrayals().remove(betrayal);
            lives.changed();
            findOut(server, betrayal, now);
        }
    }

    private static void findOut(MinecraftServer server, AiLivesSavedData.Betrayal betrayal, long now) {
        AiMemorySavedData memory = AiMemorySavedData.get(server);
        String playerName = AiChildhood.playerName(server, betrayal.player());
        AiPairMemory pair = memory.edit(betrayal.owner(), betrayal.player());
        pair.remember(new AiMemoryNote(playerName + " told " + betrayal.listener() + " my secret. I trusted them.",
                AiImportance.HIGH), AiSentiment.STRONGLY_NEGATIVE, AffectionMath.dayOf(now),
                Math.max(1, McaConversationsConfig.aiMemoriesPerPair()));
        pair.holdGrudge(now + AiSocialEffects.GRUDGE_TICKS, playerName + " told " + betrayal.listener() + " my secret");
        ServerPlayer player = server.getPlayerList().getPlayer(betrayal.player());
        Entity owner = null;
        for (ServerLevel level : server.getAllLevels()) {
            owner = level.getEntity(betrayal.owner());
            if (owner != null) {
                break;
            }
        }
        if (player == null) {
            return;
        }
        if (owner != null && owner.isAlive()) {
            if (McaConversationsConfig.aiRelationshipEffects()) {
                AiHearts.grant(server, owner, player, "ai.secret.betrayed", BETRAYAL_HEARTS, DepthClass.STANDARD,
                        ReplayPolicy.ONCE, 0, 0, "ai.secret." + betrayal.owner() + "." + betrayal.due(), now);
            }
            StateTracker.apply(owner, player, ConversationState.ANNOYED);
        }
        String ownerName = pair.villagerName().isEmpty() ? "Someone" : pair.villagerName();
        player.displayClientMessage(Component.translatable("mcaconversations.ai.secret.found_out", ownerName)
                .withStyle(ChatFormatting.RED), true);
    }

    /** What a villager knows about secrets with this player, for the prompt. */
    static List<String> promptLines(AiPairMemory pair, String playerName, boolean trusted) {
        List<String> out = new java.util.ArrayList<>();
        if (!pair.secrets().isEmpty()) {
            out.add("You have confided in " + playerName + " (the memories marked \"told in confidence\"). "
                    + "You hope they keep it to themselves.");
        } else if (trusted) {
            out.add("You trust " + playerName + " enough that you might confide something personal if the moment is "
                    + "right; if you do, mark that memory \"secret\": true.");
        }
        return out;
    }
}
