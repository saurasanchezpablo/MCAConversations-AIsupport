package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaChatAi;
import dev.otectus.mcaconversations.conversation.AgeGroup;
import dev.otectus.mcaconversations.conversation.RelationshipRoles;
import dev.otectus.mcaconversations.conversation.Relationships;
import dev.otectus.mcaconversations.progress.AffectionMath;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * AI conversations: MCA's villager chat AI, with consequences.
 *
 * <h2>Where this sits</h2>
 * MCA decides that a chat line is addressed to a villager (by name, nickname, or an open
 * conversation), picks that villager's strategy, and delivers whatever line comes back. This class
 * replaces only the middle of that, the OpenAI-strategy request, through {@code OpenAIChatAIMixin}:
 * MCA still routes, still owns the endpoint/model/token, still delivers, and Inworld characters are
 * untouched. So the integration is invisible to a player, and turning {@code ai.enabled} off gives
 * MCA's behaviour back exactly.
 *
 * <h2>One turn</h2>
 * <ol>
 *   <li><b>Server thread:</b> admit the turn (one in flight per villager-player pair, a per-player
 *       cooldown), then snapshot everything the prompt needs: MCA's prompt pieces, this mod's
 *       structured context and the pair's memories. The villager is identified by UUID from here on.</li>
 *   <li><b>Transport threads:</b> send the request; never the server thread.</li>
 *   <li><b>Server thread:</b> re-resolve the villager by UUID in the player's level; if either is gone,
 *       or out of reach, discard the turn whole. Otherwise parse and validate the reply, plan its
 *       effects ({@link AiOutcomePlan}), apply them ({@link AiOutcomeApplier}), and hand MCA the line.</li>
 * </ol>
 * A turn that fails anywhere (timeout, network, provider error, a reply with nothing usable) applies
 * nothing: every effect happens in step 3, after validation, in one server tick.
 */
public final class AiConversations {

    /** Beyond this distance a reply is discarded rather than shouted across the village. */
    private static final double MAX_REPLY_DISTANCE = 32.0;
    /** Most memories put in one prompt. */
    private static final int PROMPT_MEMORIES = 8;
    /** Longest player message forwarded, in code points (vanilla chat allows 256 characters). */
    private static final int MAX_PLAYER_MESSAGE = 256;
    /** Least time between two failure notices to one player. */
    private static final long FAILURE_NOTICE_TICKS = 200;

    private static final AiSessions SESSIONS = new AiSessions();
    /** Server-thread confined, like {@link #SESSIONS}. */
    private static final Map<UUID, Long> LAST_FAILURE_NOTICE = new HashMap<>();
    private static volatile AiTransport transport = new HttpAiTransport();

    private AiConversations() {
    }

    /**
     * Whether to take over MCA's request at all. Called from MCA's threads, so it only reads config.
     * When this is false MCA's own request runs untouched.
     */
    public static boolean enabled() {
        return McaConversationsConfig.aiEnabled() && McaChatAi.available();
    }

    /** For tests and alternative backends. */
    static void setTransport(AiTransport replacement) {
        transport = replacement == null ? new HttpAiTransport() : replacement;
    }

    // ---------------------------------------------------------------------------------------------
    // Entry points, one per MCA generation
    // ---------------------------------------------------------------------------------------------

    /**
     * MCA 7.7.1 and later: called on the server thread from {@code OpenAIChatAI.requestAndApply}, whose
     * future MCA completes into {@code ConversationManager.addMessage} on the server thread.
     */
    public static CompletableFuture<Optional<String>> answerAsync(ServerPlayer player, Object villager, String message) {
        try {
            return begin(player, villager, message);
        } catch (Throwable t) {
            McaConversations.LOGGER.error("AI conversation turn failed to start", t);
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /**
     * MCA 7.6 through 7.7.0: called from {@code OpenAIChatAI.answer} on a common-pool thread MCA
     * already dedicates to the request, and expected to return the line synchronously, as MCA's own
     * blocking HTTP call does. The turn itself still runs on the server thread and the transport
     * threads; this thread only waits for it, with a hard limit.
     */
    public static Optional<String> answerBlocking(ServerPlayer player, Object villager, String message) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null) {
            return Optional.empty();
        }
        try {
            return CompletableFuture.supplyAsync(() -> begin(player, villager, message), server)
                    .thenCompose(future -> future)
                    .get(McaConversationsConfig.aiRequestTimeoutSeconds() + 10L, TimeUnit.SECONDS);
        } catch (Throwable t) {
            // Timed out waiting for a stopping server, or interrupted: nothing was applied.
            McaConversations.LOGGER.debug("AI conversation turn abandoned", t);
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The turn
    // ---------------------------------------------------------------------------------------------

    /** Step 1, on the server thread. */
    private static CompletableFuture<Optional<String>> begin(ServerPlayer player, Object villagerObject, String rawMessage) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null || !(villagerObject instanceof Entity villager) || !McaCompat.isMcaVillager(villager)
                || player.isRemoved() || villager.isRemoved() || player.level() != villager.level()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String message = AiText.clean(rawMessage, MAX_PLAYER_MESSAGE);
        if (message.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Optional<McaChatAi.Settings> settingsRead = McaChatAi.settings();
        if (settingsRead.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        McaChatAi.Settings settings = settingsRead.get();

        UUID villagerId = villager.getUUID();
        UUID playerId = player.getUUID();
        String villagerName = McaCompat.getVillagerName(villager).orElse(villager.getName().getString());
        String playerName = player.getName().getString();
        long now = villager.level().getGameTime();
        long day = AffectionMath.dayOf(now);
        int idleTicks = McaConversationsConfig.aiConversationIdleTicks();

        SESSIONS.expire(now, idleTicks);
        AiSessions.Admission admission = SESSIONS.admit(villagerId, playerId, now,
                McaConversationsConfig.aiTurnCooldownTicks(), idleTicks);
        if (admission != AiSessions.Admission.ADMITTED) {
            player.displayClientMessage(Component.translatable(admission == AiSessions.Admission.IN_FLIGHT
                    ? "mcaconversations.ai.busy" : "mcaconversations.ai.wait", villagerName)
                    .withStyle(ChatFormatting.GRAY), true);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        try {
            AiPolicy policy = new AiPolicy(McaConversationsConfig.aiRelationshipEffects(),
                    McaConversationsConfig.aiGameplayEffects(), McaConversationsConfig.aiMinConfidence(),
                    McaConversationsConfig.aiMemoriesPerPair());
            AiSessions.Session session = SESSIONS.session(villagerId, playerId);
            AiMemorySavedData memoryData = AiMemorySavedData.get(server);

            // Promises are judged by what the player did since they last talked, before the villager
            // speaks, so a promise kept or broken is already part of what the villager knows.
            AiPromises.sweep(server, villager, player, now, day);
            AiSocial.Turn turn = AiSocial.capture(server, villager, player, villagerName, playerName, policy, now, day);
            List<AiContextSection> sections = new java.util.ArrayList<>(AiContextCollector.collect(villager, player,
                    villagerName, playerName, memoryData.turns(villagerId, playerId),
                    memoryData.lastTalkDay(villagerId, playerId), day));
            sections.addAll(turn.sections());

            List<McaChatAi.Command> commands = settings.useTools() && policy.gameplayEffects()
                    ? McaChatAi.activeCommands(villager, player) : List.of();
            Set<String> offered = new LinkedHashSet<>();
            commands.forEach(c -> offered.add(c.id()));

            AiPromptInput input = new AiPromptInput(settings.model(), settings.systemPrompt(), settings.inHouse(),
                    settings.includeSessionInfo(), settings.longTermMemory(), settings.sharedLongTermMemory(),
                    replyLanguage(player, settings.language()), McaConversationsConfig.aiRequestJsonMode(),
                    player.serverLevel().getSeed(), playerId, villagerId, playerName, villagerName,
                    McaChatAi.describeVillager(villager, player, playerName, villagerName),
                    McaChatAi.editedContext(villager, player),
                    safetyRule(villager, player),
                    sections,
                    policy.memoriesPerPair() > 0 ? memoryData.recall(villagerId, playerId, day, PROMPT_MEMORIES) : List.of(),
                    day,
                    commands.stream().map(c -> new AiPromptInput.CommandOption(c.id(), c.description())).toList(),
                    session.transcript(), message, AiSocial.offers(turn), !turn.bystanderIds().isEmpty());
            String body = AiPromptBuilder.body(input);
            if (McaConversationsConfig.debugAi()) {
                McaConversations.LOGGER.info("[ai] request villager={} player={} chars={} memories={} commands={}",
                        villagerId, playerName, body.length(), input.memories().size(), offered);
            }

            Duration timeout = Duration.ofSeconds(McaConversationsConfig.aiRequestTimeoutSeconds());
            return transport.send(settings.endpoint(), settings.tokenFor(playerName), body, timeout)
                    .exceptionally(t -> AiHttpResult.failed("network_error"))
                    .thenApplyAsync(result -> complete(server, playerId, villagerId, villagerName, message, result,
                            policy, offered, settings, turn), server)
                    .exceptionally(t -> {
                        McaConversations.LOGGER.error("AI conversation turn failed; nothing was applied", t);
                        server.execute(() -> SESSIONS.finish(villagerId, playerId, now));
                        return Optional.empty();
                    });
        } catch (RuntimeException | Error t) {
            // Never leave the pair stuck "in flight" because building the request threw.
            SESSIONS.finish(villagerId, playerId, now);
            throw t;
        }
    }

    /** Step 3, on the server thread. */
    private static Optional<String> complete(MinecraftServer server, UUID playerId, UUID villagerId, String villagerName,
                                             String message, AiHttpResult result, AiPolicy policy, Set<String> offered,
                                             McaChatAi.Settings settings, AiSocial.Turn turn) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        Entity villager = player == null ? null : player.serverLevel().getEntity(villagerId);
        long now = villager != null ? villager.level().getGameTime() : server.overworld().getGameTime();
        try {
            if (player == null || villager == null || player.isRemoved() || villager.isRemoved()
                    || !villager.isAlive() || player.distanceTo(villager) > MAX_REPLY_DISTANCE) {
                // The player left, the villager unloaded, died or changed dimension: the turn is void.
                if (McaConversationsConfig.debugAi()) {
                    McaConversations.LOGGER.info("[ai] discarded: participants no longer together (villager={})",
                            villagerId);
                }
                return Optional.empty();
            }
            if (result.error().isPresent()) {
                notifyFailure(player, villagerName, result.error().get(), now);
                return Optional.empty();
            }
            Optional<AiReply> parsed = AiReplyParser.parse(result.content().orElse(""));
            if (parsed.isEmpty()) {
                notifyFailure(player, villagerName, "malformed_response", now);
                return Optional.empty();
            }
            AiReply reply = parsed.get();
            AiOutcomePlan plan = AiOutcomePlan.of(reply, policy, turn.facts());
            AiSessions.Session session = SESSIONS.session(villagerId, playerId);
            long day = AffectionMath.dayOf(now);
            AiOutcomeApplier.Applied applied = AiOutcomeApplier.apply(server, villager, player, reply, plan, policy,
                    session, offered, now, day);
            AiSocialEffects.Applied social = AiSocialEffects.apply(server, villager, player, villagerName, plan, reply,
                    turn, now, day);
            // How the line should sound, sent ahead of MCA delivering it.
            AiVoice.direct(player, villager, reply.dialogue(), reply.emotion(), reply.deliveryOrDefault(), turn.facts());
            session.recordExchange(message, reply.dialogue());
            if (McaConversationsConfig.debugAi()) {
                McaConversations.LOGGER.info("[ai] reply villager={} player={} structured={} impact={} confidence={} "
                                + "emotion={} memory={} effects={} command='{}' | planned hearts={} state={} dispositions={} "
                                + "| applied granted={} measured={} reason={} remembered={} commandRan={}",
                        villagerId, player.getName().getString(), reply.structured(), reply.sentiment().key(),
                        reply.confidence(), reply.emotion().key(), reply.memory().map(AiMemoryNote::text).orElse("-"),
                        reply.effects(), reply.command(), plan.authoredHearts(), plan.state().orElse(null),
                        plan.dispositions(), applied.grantedHearts(), applied.measuredHearts(), applied.heartReason(),
                        applied.remembered(), applied.commandRan());
                McaConversations.LOGGER.info("[ai] social villager={} interjection={} promise={} wish={} quest={} unlock={} "
                                + "opinion={} directions={} tradeMood={} grudge={} forgiven={} gossip={} grieving={} romance={}",
                        villagerId, social.interjected(), social.promise(), social.wish(), social.quest(), social.unlock(),
                        social.opinion(), social.directions(), social.tradeMood(), social.grudge(), social.forgiven(),
                        social.gossip(), turn.facts().grieving(), turn.facts().romanceAllowed());
            }
            return Optional.of(reply.dialogue());
        } finally {
            SESSIONS.finish(villagerId, playerId, now);
        }
    }

    /** The language to reply in: the player's game language when known, else MCA's hint. */
    private static String replyLanguage(ServerPlayer player, String mcaLanguage) {
        String byClient = AiVoice.languageName(AiVoice.clientLanguage(player));
        return byClient != null ? byClient : mcaLanguage;
    }

    /**
     * MCA's own rule for children and relatives, worded as MCA words it, so the integration is never
     * less careful than MCA's own request. Romance is otherwise left to MCA's relationship prompt.
     */
    private static String safetyRule(Entity villager, ServerPlayer player) {
        AgeGroup age = McaCompat.ageGroup(villager);
        if (age == AgeGroup.BABY || age == AgeGroup.TODDLER || age == AgeGroup.CHILD) {
            return "You are a child/baby and MUST NOT flirt with the player or use any romantic or suggestive "
                    + "language. Keep your responses innocent, child-like, and age-appropriate.";
        }
        RelationshipRoles roles = Relationships.rolesOf(villager, player);
        if (roles.parent() || roles.child() || roles.sibling()) {
            return "You are related to the player and MUST NOT flirt with them or use romantic/suggestive "
                    + "language. Keep your responses strictly familial.";
        }
        return "";
    }

    /** MCA's messages for MCA's hosted-service errors; one quiet line of our own for anything else. */
    private static void notifyFailure(ServerPlayer player, String villagerName, String error, long now) {
        McaConversations.LOGGER.debug("AI conversation request failed: {}", error);
        switch (error) {
            case "invalid_model" -> player.sendSystemMessage(Component.literal("Invalid model!").withStyle(ChatFormatting.RED));
            case "limit" -> player.sendSystemMessage(Component.translatable("mca.limit.patreon").withStyle(s -> s
                    .withColor(ChatFormatting.GOLD)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL,
                            "https://github.com/Luke100000/minecraft-comes-alive/wiki/GPT3-based-conversations#increase-conversation-limit"))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.translatable("mca.limit.patreon.hover")))));
            case "limit_premium" -> player.sendSystemMessage(
                    Component.translatable("mca.limit.premium").withStyle(ChatFormatting.RED));
            default -> {
                Long last = LAST_FAILURE_NOTICE.get(player.getUUID());
                if (last == null || now < last || now - last >= FAILURE_NOTICE_TICKS) {
                    LAST_FAILURE_NOTICE.put(player.getUUID(), now);
                    player.displayClientMessage(Component.translatable("mcaconversations.ai.unavailable", villagerName)
                            .withStyle(ChatFormatting.GRAY), true);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle, from ConversationsEvents
    // ---------------------------------------------------------------------------------------------

    /** A villager died: forget every memory every player shared with it. */
    public static void onVillagerDeath(MinecraftServer server, UUID villager) {
        SESSIONS.removeVillager(villager);
        AiMemorySavedData.get(server).removeVillager(villager);
    }

    /**
     * A villager died: their family mourn them (partner, parents, children, siblings, from MCA's family
     * tree), then everything the dead villager carried is forgotten. Server thread.
     */
    public static void onVillagerDied(net.minecraft.world.entity.Entity deceased) {
        MinecraftServer server = deceased.getServer();
        if (server == null || !(deceased.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        try {
            String name = McaCompat.getVillagerName(deceased).orElse(deceased.getName().getString());
            long day = AffectionMath.dayOf(level.getGameTime());
            UUID id = deceased.getUUID();
            AiMemorySavedData data = AiMemorySavedData.get(server);
            McaCompat.getPartnerFromTree(level, id).ifPresent(p -> data.recordBereavement(p, new AiBereavement(name, "partner", day)));
            // Seen from the mourner's side: the dead villager's parents lost a child, and so on.
            McaCompat.getParents(level, id).forEach(p -> data.recordBereavement(p, new AiBereavement(name, "child", day)));
            McaCompat.getChildren(level, id).forEach(c -> data.recordBereavement(c, new AiBereavement(name, "parent", day)));
            McaCompat.getSiblings(level, id).forEach(s -> data.recordBereavement(s, new AiBereavement(name, "sibling", day)));
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("AI bereavement record failed", t);
        }
        onVillagerDeath(server, deceased.getUUID());
    }

    /** A gift was accepted (from GiftTracker): promises and wishes. Server thread. */
    public static void onGiftAccepted(net.minecraft.world.entity.Entity villager, ServerPlayer player,
                                      net.minecraft.world.item.ItemStack stack) {
        if (!enabled() || !McaConversationsConfig.aiGameplayEffects() || player.getServer() == null) {
            return;
        }
        try {
            long now = villager.level().getGameTime();
            AiPromises.onGift(player.getServer(), villager, player, stack, now, AffectionMath.dayOf(now));
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("AI gift observation failed", t);
        }
    }

    /**
     * Whether this villager refuses to trade with this player right now (a grudge from an AI
     * conversation). Says why, in the villager's voice, when it refuses. Server thread.
     */
    public static boolean refusesTrade(net.minecraft.world.entity.Entity villager, ServerPlayer player) {
        if (!enabled() || !McaConversationsConfig.aiGameplayEffects() || player.getServer() == null
                || !McaCompat.isMcaVillager(villager)) {
            return false;
        }
        try {
            long now = villager.level().getGameTime();
            boolean refuses = AiMemorySavedData.get(player.getServer()).peek(villager.getUUID(), player.getUUID())
                    .map(pair -> pair.grudge(now)).orElse(false);
            if (refuses) {
                String name = McaCompat.getVillagerName(villager).orElse(villager.getName().getString());
                player.displayClientMessage(Component.literal(name + ": ").append(AiLines.variant("refuse_trade"))
                        .withStyle(ChatFormatting.GRAY), false);
            }
            return refuses;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Every server tick: delayed effects. */
    public static void tick(MinecraftServer server) {
        AiTasks.drain(server.overworld().getGameTime());
    }

    public static void onPlayerLogout(UUID player) {
        SESSIONS.removePlayer(player);
        LAST_FAILURE_NOTICE.remove(player);
    }

    /** Server start: drop memories that faded while the world was closed. */
    public static void onServerStarted(MinecraftServer server) {
        AiMemorySavedData.get(server).prune(AffectionMath.dayOf(server.overworld().getGameTime()));
    }

    /** Server stopped: no conversation survives a restart except as memory. */
    public static void onServerStopped() {
        SESSIONS.clear();
        LAST_FAILURE_NOTICE.clear();
        AiTasks.clear();
    }
}
