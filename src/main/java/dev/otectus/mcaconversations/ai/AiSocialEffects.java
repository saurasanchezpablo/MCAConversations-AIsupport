package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.QuestsBridge;
import dev.otectus.mcaconversations.gossip.GossipEvent;
import dev.otectus.mcaconversations.gossip.GossipEventType;
import dev.otectus.mcaconversations.gossip.GossipSavedData;
import dev.otectus.mcaconversations.history.Confidence;
import dev.otectus.mcaconversations.history.History;
import dev.otectus.mcaconversations.history.PrivacyLevel;
import dev.otectus.mcaconversations.history.SocialOpinionRecord;
import dev.otectus.mcaconversations.state.ConversationState;
import dev.otectus.mcaconversations.state.MemoryIds;
import dev.otectus.mcaconversations.state.StateTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.Villager;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Applies the social and gameplay half of an {@link AiOutcomePlan}, on the server thread, each
 * effect through the system that already owns it and each step isolated from the others:
 * <ul>
 *   <li>promises and wishes: this mod's AI memory, judged later by {@link AiPromises};</li>
 *   <li>quests: MCA: Quests' own commission menu, restricted to the one offered quest;</li>
 *   <li>a deeper topic: the player-scoped unlock memory authored dialogue already checks;</li>
 *   <li>opinions of neighbours: this mod's AI memory and, when enabled, social opinions;</li>
 *   <li>directions: {@link AiDirections};</li>
 *   <li>prices: vanilla villager gossip (MINOR_POSITIVE / MINOR_NEGATIVE), which is what MCA
 *       villagers' trade prices are made of, which spreads between villagers and fades by itself;</li>
 *   <li>grudges and forgiveness: this mod's AI memory and ANNOYED state, plus MCA: Reputation;</li>
 *   <li>interjections: the bystander's own MCA message queue;</li>
 *   <li>village talk: this mod's gossip log.</li>
 * </ul>
 */
final class AiSocialEffects {

    /** How long a grudge lasts unless forgiven: one and a half Minecraft days. */
    static final long GRUDGE_TICKS = 36_000L;
    /** Most vanilla trade-reputation points one pair may move in a day, either way. */
    static final int TRADE_MOOD_DAILY_CAP = 20;
    /** Delay before a quest menu opens, so the villager's line lands first. */
    static final long QUEST_MENU_DELAY_TICKS = 50;
    /** Story attribute length the gossip log accepts. */
    static final int STORY_LENGTH = 120;
    /** A bystander chimes in a beat after the villager's reply. */
    static final long INTERJECTION_DELAY_TICKS = 40;

    record Applied(boolean promise, boolean wish, boolean quest, boolean unlock, boolean opinion, boolean directions,
                   int tradeMood, boolean grudge, boolean forgiven, boolean interjected, boolean gossip) {
    }

    private AiSocialEffects() {
    }

    static Applied apply(MinecraftServer server, Entity villager, ServerPlayer player, String villagerName,
                         AiOutcomePlan plan, AiReply reply, AiSocial.Turn turn, long now, long day) {
        AiMemorySavedData data = AiMemorySavedData.get(server);
        UUID villagerId = villager.getUUID();
        UUID playerId = player.getUUID();
        String playerName = player.getName().getString();
        ServerLevel level = player.serverLevel();

        boolean promise = attempt("promise", () -> plan.promise().map(request -> {
            if (!request.isVisit() && !AiPromises.itemExists(request.item())) {
                return false; // a promise to bring something that does not exist could never be kept
            }
            return data.edit(villagerId, playerId).addPromise(request, day, AiTurnFacts.MAX_OPEN_PROMISES)
                    .map(made -> {
                        AiReputationLink.promise(player, villager, made, AiReputationLink.PROMISE_MADE, "made");
                        return true;
                    }).orElse(false);
        }).orElse(false));

        boolean wish = attempt("wish", () -> plan.wish().filter(w -> AiPromises.itemExists(w.item())).map(w -> {
            data.edit(villagerId, playerId).setWish(new AiWish(w.item(), day, day + w.days(), w.summary()));
            return true;
        }).orElse(false));

        boolean quest = attempt("quest", () -> plan.questOffer().map(id -> {
            QuestsBridge.QuestQueries queries = QuestsBridge.queries();
            if (queries == null) {
                return false;
            }
            AiTasks.schedule(now + QUEST_MENU_DELAY_TICKS, () -> {
                ServerPlayer live = server.getPlayerList().getPlayer(playerId);
                Entity speaker = live == null ? null : live.serverLevel().getEntity(villagerId);
                if (live != null && speaker != null && speaker.isAlive() && live.distanceTo(speaker) <= 16) {
                    queries.offerQuest(live, speaker, id);
                }
            });
            return true;
        }).orElse(false));

        boolean unlock = attempt("unlock", () -> plan.unlockTopic().map(topic -> {
            McaCompat.rememberForever(villager, MemoryIds.playerScoped(MemoryIds.unlock(topic), playerId));
            return true;
        }).orElse(false));

        boolean opinion = attempt("opinion", () -> plan.opinion().map(o -> {
            UUID target = resolve(turn.neighbourIds(), o.about());
            if (target == null || target.equals(villagerId)) {
                return false;
            }
            String targetName = turn.neighbourIds().entrySet().stream().filter(e -> e.getValue().equals(target))
                    .map(Map.Entry::getKey).findFirst().orElse(o.about());
            String cause = o.cause().isEmpty() ? playerName + " told me something" : o.cause();
            data.adjustOpinion(villagerId, target, targetName, o.axis(), o.direction(), cause, day);
            // Mirror into the living-histories opinion store when it is on, so authored content sees it.
            History.recordOpinion(villager, new SocialOpinionRecord(target, o.axis(), o.direction(), cause,
                    Confidence.LIKELY, PrivacyLevel.DISCREET, day, OptionalLong.of(day + 30)));
            return true;
        }).orElse(false));

        boolean directions = attempt("directions", () -> plan.directions().map(token -> {
            AiSocial.Place place = turn.places().get(token);
            if (place == null) {
                return false;
            }
            AiDirections.give(level, villager, player, place, villagerName, data.edit(villagerId, playerId), now, day);
            return true;
        }).orElse(false));

        int tradeMood = 0;
        if (plan.tradeMood() != 0 && villager instanceof Villager vanilla) {
            int granted = data.edit(villagerId, playerId).claimTradeMood(plan.tradeMood(), day, TRADE_MOOD_DAILY_CAP);
            if (granted != 0) {
                try {
                    vanilla.getGossips().add(playerId, granted > 0 ? GossipType.MINOR_POSITIVE : GossipType.MINOR_NEGATIVE,
                            Math.abs(granted));
                    tradeMood = granted;
                } catch (Throwable t) {
                    McaConversations.LOGGER.debug("AI trade mood failed", t);
                }
            }
        }

        boolean forgiven = attempt("forgive", () -> {
            if (!plan.forgive()) {
                return false;
            }
            AiPairMemory pair = data.edit(villagerId, playerId);
            pair.forgive();
            // Let the lingering annoyance lapse now rather than in half a day.
            McaCompat.remember(villager, MemoryIds.playerScoped(ConversationState.ANNOYED.memoryId(), playerId), 1);
            pair.remember(new AiMemoryNote(playerName + " apologised, and I forgave them.", AiImportance.MEDIUM),
                    AiSentiment.POSITIVE, day, McaConversationsConfig.aiMemoriesPerPair());
            AiReputationLink.apology(player, villager, now);
            return true;
        });

        boolean grudge = attempt("grudge", () -> {
            if (!plan.grudge()) {
                return false;
            }
            String reason = reply.memory().map(AiMemoryNote::text).orElse(playerName + " hurt me");
            data.edit(villagerId, playerId).holdGrudge(now + GRUDGE_TICKS, reason);
            StateTracker.apply(villager, player, ConversationState.ANNOYED, GRUDGE_TICKS);
            return true;
        });

        boolean interjected = attempt("interjection", () -> plan.interjection().map(line -> {
            UUID speakerId = resolve(turn.bystanderIds(), line.speaker());
            Entity speaker = speakerId == null ? null : level.getEntity(speakerId);
            if (speaker == null || !speaker.isAlive() || speaker.distanceTo(player) > AiSocial.BYSTANDER_RADIUS * 2) {
                return false;
            }
            String speakerName = McaCompat.getVillagerName(speaker).orElse(line.speaker());
            // A beat after the villager's own reply, the way someone chimes in.
            AiLines.sayLater(speaker, player, net.minecraft.network.chat.Component.literal(line.message()), speakerName,
                    now, INTERJECTION_DELAY_TICKS, AiEmotion.NEUTRAL, dev.otectus.mcaconversations.voice.VoiceIntent.STATEMENT);
            // The bystander now remembers having been part of it, a little.
            data.edit(speakerId, playerId).remember(new AiMemoryNote("I joined in when " + playerName + " was talking with "
                    + villagerName + ".", AiImportance.LOW), reply.sentiment(), day, McaConversationsConfig.aiMemoriesPerPair());
            return true;
        }).orElse(false));

        boolean gossip = attempt("gossip", () -> plan.gossip().map(tone -> publish(server, level, villager, villagerName,
                player, playerName, tone, plan.memory().map(AiMemoryNote::text).orElse(""), now)).orElse(false));

        return new Applied(promise, wish, quest, unlock, opinion, directions, tradeMood, grudge, forgiven, interjected, gossip);
    }

    /** Adds the story to the village's gossip log; the log refuses a duplicate story about the same pair. */
    private static boolean publish(MinecraftServer server, ServerLevel level, Entity villager, String villagerName,
                                   ServerPlayer player, String playerName, AiOutcomePlan.GossipTone tone, String story,
                                   long now) {
        if (!McaConversationsConfig.COMMON.enableGossip.get()) {
            return false;
        }
        OptionalInt villageId = McaCompat.getHomeVillageId(villager);
        if (villageId.isEmpty()) {
            villageId = McaCompat.findNearestVillageId(level, villager.blockPosition(), 128);
        }
        if (villageId.isEmpty()) {
            return false;
        }
        GossipEventType type = tone == AiOutcomePlan.GossipTone.KIND ? GossipEventType.PLAYER_KINDNESS
                : GossipEventType.PLAYER_CRUELTY;
        String trimmed = AiText.clean(story, STORY_LENGTH);
        GossipEvent event = new GossipEvent(UUID.randomUUID(), type, villageId.getAsInt(), now, player.getUUID(),
                playerName, Optional.of(villager.getUUID()), villagerName,
                trimmed.isEmpty() ? Map.of() : Map.of("story", trimmed));
        return GossipSavedData.get(server).addEvent(event, McaConversationsConfig.COMMON.maxEventsPerVillage.get());
    }

    private static UUID resolve(Map<String, UUID> byName, String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, UUID> entry : byName.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name.trim())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private interface Step {
        boolean run() throws Exception;
    }

    private static boolean attempt(String what, Step step) {
        try {
            return step.run();
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("AI {} effect failed; skipped", what, t);
            return false;
        }
    }
}
