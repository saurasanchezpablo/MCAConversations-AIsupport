package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.conversation.AgeGroup;
import dev.otectus.mcaconversations.conversation.DepthClass;
import dev.otectus.mcaconversations.progress.AffectionMath;
import dev.otectus.mcaconversations.progress.ReplayPolicy;
import dev.otectus.mcaconversations.state.ConversationState;
import dev.otectus.mcaconversations.state.StateTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Children remember. While a villager is small, how each player treats them adds up: kind words and
 * gifts one way, cruelty and blows the other. When the child grows up, the grown villager carries
 * that with them: a fond memory of the one who was good to them, or a cold one. They feel it in how
 * they treat that player, and every conversation knows it.
 */
final class AiChildhood {

    static final int LIMIT = 20;
    static final int GROWN_HEARTS = 2;

    private AiChildhood() {
    }

    static boolean child(AgeGroup age) {
        return age == AgeGroup.BABY || age == AgeGroup.TODDLER || age == AgeGroup.CHILD;
    }

    /** How a childhood score reads, or empty when it was nothing much either way. Pure. */
    static String tone(int score) {
        if (score >= 6) {
            return "very kind";
        }
        if (score >= 2) {
            return "kind";
        }
        if (score <= -6) {
            return "cruel";
        }
        return score <= -2 ? "unkind" : "";
    }

    /** What a turn with a child adds: strong feelings count double. Pure. */
    static int weigh(AiSentiment sentiment) {
        return switch (sentiment) {
            case STRONGLY_POSITIVE -> 2;
            case POSITIVE -> 1;
            case NEGATIVE -> -1;
            case STRONGLY_NEGATIVE -> -2;
            default -> 0;
        };
    }

    private static void add(MinecraftServer server, Entity villager, UUID player, int delta, boolean gift) {
        if (delta == 0 || !child(McaCompat.ageGroup(villager))) {
            return;
        }
        AiLivesSavedData.Childhood c = AiLivesSavedData.get(server).editChildhood(villager.getUUID(), player);
        c.score = Math.max(-LIMIT, Math.min(LIMIT, c.score + delta));
        if (gift) {
            c.gifts++;
        }
    }

    static void onTurn(MinecraftServer server, Entity villager, ServerPlayer player, AiSentiment sentiment) {
        add(server, villager, player.getUUID(), weigh(sentiment), false);
    }

    static void onGift(MinecraftServer server, Entity villager, ServerPlayer player) {
        add(server, villager, player.getUUID(), 1, true);
    }

    static void onHurt(MinecraftServer server, Entity villager, ServerPlayer player) {
        add(server, villager, player.getUUID(), -3, false);
    }

    /** A child of the village has grown up: what they remember of each player who knew them. */
    static void grewUp(MinecraftServer server, Entity villager) {
        AiLivesSavedData lives = AiLivesSavedData.get(server);
        long day = AffectionMath.dayOf(server.overworld().getGameTime());
        String name = McaCompat.getVillagerName(villager).orElse(villager.getName().getString());
        for (Map.Entry<UUID, AiLivesSavedData.Childhood> entry : lives.childhoodOf(villager.getUUID()).entrySet()) {
            AiLivesSavedData.Childhood c = entry.getValue();
            String tone = tone(c.score);
            if (c.remembered || tone.isEmpty()) {
                continue;
            }
            c.remembered = true;
            lives.changed();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            String playerName = player != null ? player.getName().getString() : playerName(server, entry.getKey());
            boolean warm = c.score > 0;
            String note = warm ? "I have grown up now, and I still remember how " + tone + " " + playerName
                    + " was to me when I was little" + (c.gifts > 0 ? " (the gifts, too)" : "") + "."
                    : "I have grown up now, and I have not forgotten how " + tone + " " + playerName
                    + " was to me when I was a child.";
            AiMemorySavedData.get(server).edit(villager.getUUID(), entry.getKey()).remember(new AiMemoryNote(note,
                    AiImportance.HIGH), warm ? AiSentiment.STRONGLY_POSITIVE : AiSentiment.STRONGLY_NEGATIVE, day,
                    Math.max(1, McaConversationsConfig.aiMemoriesPerPair()));
            if (player != null && McaConversationsConfig.aiRelationshipEffects()) {
                AiHearts.grant(server, villager, player, "ai.childhood", warm ? GROWN_HEARTS : -GROWN_HEARTS,
                        DepthClass.STANDARD, ReplayPolicy.ONCE, 0, 0, "ai.childhood." + villager.getUUID(),
                        villager.level().getGameTime());
                if (warm) {
                    StateTracker.apply(villager, player, ConversationState.GRATEFUL);
                }
            }
            if (McaConversationsConfig.debugAi()) {
                dev.otectus.mcaconversations.McaConversations.LOGGER.info("[ai] {} grew up remembering {} as {}", name,
                        playerName, tone);
            }
        }
    }

    /** A player's name, online or not, from the server's profile cache. */
    static String playerName(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getName().getString();
        }
        try {
            var cache = server.getProfileCache();
            if (cache != null) {
                return cache.get(player).map(com.mojang.authlib.GameProfile::getName).orElse("a traveller");
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "a traveller";
    }

    static List<String> promptLines(MinecraftServer server, Entity villager, ServerPlayer player) {
        List<String> out = new ArrayList<>();
        AiLivesSavedData.get(server).childhood(villager.getUUID(), player.getUUID()).ifPresent(c -> {
            String tone = tone(c.score);
            if (tone.isEmpty()) {
                return;
            }
            String name = player.getName().getString();
            if (child(McaCompat.ageGroup(villager))) {
                out.add(name + " has been " + tone + " to you" + (c.gifts > 0 ? " (" + c.gifts + " gifts)" : "")
                        + ". Children notice these things.");
            } else {
                out.add("When you were a child, " + name + " was " + tone + " to you"
                        + (c.gifts > 0 ? " and gave you presents" : "") + ". You remember it, and it colours how you see them.");
            }
        });
        return out;
    }
}
