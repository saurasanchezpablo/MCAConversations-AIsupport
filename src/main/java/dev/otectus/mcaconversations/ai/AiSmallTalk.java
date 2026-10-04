package dev.otectus.mcaconversations.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaChatAi;
import dev.otectus.mcaconversations.conversation.AgeGroup;
import dev.otectus.mcaconversations.progress.AffectionMath;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Villagers living their own lives. Two neighbours standing close together, near a player but not
 * talking to them, sometimes have a short chat: two to four lines the player overhears if close
 * enough. They stop, face each other and talk, written by the model from who they are, what they feel
 * about each other, what is going on in the village and what they think of the player standing by.
 * A chat can leave them a little fonder of each other, or a little cooler.
 *
 * <p>Once a day, old grudges between neighbours soften a little: time heals, mostly.
 */
final class AiSmallTalk {

    static final int CHECK_INTERVAL = 200;
    static final double PLAYER_RADIUS = 20.0;
    static final double PAIR_DISTANCE = 5.0;
    static final double HEAR_RADIUS = 12.0;
    static final long PAIR_COOLDOWN = 24_000;
    static final double CHANCE = 0.35;
    static final long LINE_GAP = 60;
    static final int MAX_LINES = 4;
    static final int MAX_LINE_CHARS = 160;

    /** One overheard exchange, as parsed. */
    record Line(String speaker, String text) {
    }

    record Exchange(List<Line> lines, int warmth, String topic) {
    }

    private static final Map<UUID, Long> LAST_BY_PLAYER = new HashMap<>();
    private static final Map<String, Long> LAST_BY_PAIR = new HashMap<>();
    private static final java.util.Set<UUID> TALKING = new java.util.HashSet<>();

    private AiSmallTalk() {
    }

    static boolean busy(UUID villager) {
        return TALKING.contains(villager);
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_INTERVAL != 0 || !McaConversationsConfig.aiVillagerChatter()) {
            return;
        }
        long now = server.overworld().getGameTime();
        long cooldown = McaConversationsConfig.aiVillagerChatterCooldownTicks();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Long last = LAST_BY_PLAYER.get(player.getUUID());
            if (player.isSpectator() || (last != null && now - last < cooldown)
                    || AiConversations.partner(player.getUUID(), now).isPresent()) {
                continue;
            }
            Optional<Entity[]> pair = pair(player, now);
            if (pair.isEmpty() || ThreadLocalRandom.current().nextDouble() >= CHANCE) {
                continue;
            }
            LAST_BY_PLAYER.put(player.getUUID(), now);
            LAST_BY_PAIR.put(pairKey(pair.get()[0].getUUID(), pair.get()[1].getUUID()), now);
            start(server, player, pair.get()[0], pair.get()[1]);
        }
    }

    private static Optional<Entity[]> pair(ServerPlayer player, long now) {
        List<Entity> around = player.serverLevel().getEntities(player, player.getBoundingBox().inflate(PLAYER_RADIUS),
                e -> e.isAlive() && McaCompat.isMcaVillager(e) && free(e, now));
        for (int i = 0; i < around.size(); i++) {
            for (int j = i + 1; j < around.size(); j++) {
                Entity a = around.get(i);
                Entity b = around.get(j);
                Long last = LAST_BY_PAIR.get(pairKey(a.getUUID(), b.getUUID()));
                if (a.distanceTo(b) <= PAIR_DISTANCE && (last == null || now - last >= PAIR_COOLDOWN)
                        && McaCompat.getVillagerName(a).isPresent() && McaCompat.getVillagerName(b).isPresent()
                        && !McaCompat.getVillagerName(a).equals(McaCompat.getVillagerName(b))) {
                    return Optional.of(new Entity[]{a, b});
                }
            }
        }
        return Optional.empty();
    }

    private static boolean free(Entity villager, long now) {
        AgeGroup age = McaCompat.ageGroup(villager);
        UUID id = villager.getUUID();
        return (age == AgeGroup.ADULT || age == AgeGroup.TEEN || age == AgeGroup.CHILD)
                && !(villager instanceof LivingEntity l && l.isSleeping()) && !McaCompat.isPanicking(villager)
                && McaCompat.isInteractingWith(villager).isEmpty() && !AiConversations.inConversation(id, now)
                && AiWork.job(id).isEmpty() && !AiErrands.busy(id) && !AiBuild.busy(id) && !TALKING.contains(id)
                && !dev.otectus.mcaconversations.compat.mca.McaHandles.silentVoice(villager);
    }

    private static String pairKey(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "/" + b : b + "/" + a;
    }

    // --- asking the model ----------------------------------------------------------------------------------

    private static void start(MinecraftServer server, ServerPlayer player, Entity a, Entity b) {
        Optional<McaChatAi.Settings> settings = McaChatAi.settings();
        if (settings.isEmpty()) {
            return;
        }
        String nameA = McaCompat.getVillagerName(a).orElse("?");
        String nameB = McaCompat.getVillagerName(b).orElse("?");
        String body = body(settings.get(), prompt(server, player, a, nameA, b, nameB));
        UUID aId = a.getUUID();
        UUID bId = b.getUUID();
        UUID playerId = player.getUUID();
        TALKING.add(aId);
        TALKING.add(bId);
        AiConversations.transport().send(settings.get().endpoint(), settings.get().tokenFor(player.getName().getString()),
                        body, Duration.ofSeconds(McaConversationsConfig.aiRequestTimeoutSeconds()))
                .exceptionally(t -> AiHttpResult.failed("network_error"))
                .thenAcceptAsync(result -> {
                    try {
                        Optional<Exchange> exchange = result.content().flatMap(c -> parse(c, nameA, nameB));
                        ServerPlayer listener = server.getPlayerList().getPlayer(playerId);
                        Entity ea = listener == null ? null : listener.serverLevel().getEntity(aId);
                        Entity eb = listener == null ? null : listener.serverLevel().getEntity(bId);
                        if (exchange.isEmpty() || ea == null || eb == null || !ea.isAlive() || !eb.isAlive()
                                || ea.distanceTo(eb) > PAIR_DISTANCE * 2) {
                            TALKING.remove(aId);
                            TALKING.remove(bId);
                            return;
                        }
                        play(server, listener.serverLevel(), ea, nameA, eb, nameB, exchange.get());
                    } catch (Throwable t) {
                        McaConversations.LOGGER.debug("villager chatter failed", t);
                        TALKING.remove(aId);
                        TALKING.remove(bId);
                    }
                }, server);
    }

    private static String prompt(MinecraftServer server, ServerPlayer player, Entity a, String nameA, Entity b,
                                 String nameB) {
        StringBuilder sb = new StringBuilder();
        sb.append("Write a short conversation two villagers in a Minecraft village have between themselves, which a ")
                .append("passer-by overhears. They are real people with their own lives, not quest givers. Keep it ")
                .append("natural and specific: small worries, work, family, gossip, the weather, what is going on in the ")
                .append("village, a joke, a disagreement. Never mention games, players as a concept, or the real world.\n\n");
        describe(sb, server, a, nameA, b, nameB);
        describe(sb, server, b, nameB, a, nameA);
        List<String> news = AiVillageEvents.villageNews(server, a);
        if (!news.isEmpty()) {
            sb.append("Going on in the village: ").append(String.join("; ", news)).append(".\n");
        }
        String playerName = player.getName().getString();
        sb.append(playerName).append(" (a traveller they know of) is standing nearby; they may glance at them or ")
                .append("mention them in passing, but they are talking to each other, not to ").append(playerName).append(".\n");
        String language = AiVoice.languageName(AiVoice.clientLanguage(player));
        sb.append("Write it in ").append(language == null ? "English" : language).append(".\n\n");
        sb.append("Reply with exactly one JSON object: {\"lines\": [{\"speaker\": \"").append(nameA)
                .append("\" or \"").append(nameB).append("\", \"text\": \"one short spoken line\"}, ... 2 to 4 lines], ")
                .append("\"topic\": \"what they talked about, a few words\", \"warmth\": -1, 0 or 1 (did it bring them ")
                .append("closer, or set them against each other)}");
        return sb.toString();
    }

    private static void describe(StringBuilder sb, MinecraftServer server, Entity self, String name, Entity other,
                                 String otherName) {
        List<String> parts = new ArrayList<>();
        McaCompat.getProfessionId(self).map(AiContextFormat::words).filter(p -> !p.isEmpty() && !p.equals("none"))
                .ifPresent(parts::add);
        McaCompat.getPersonality(self).map(AiContextFormat::words).ifPresent(p -> parts.add(p + " personality"));
        McaCompat.getMoodName(self).map(AiContextFormat::words).ifPresent(m -> parts.add("mood: " + m));
        parts.add(McaCompat.ageGroup(self).name().toLowerCase(Locale.ROOT));
        if (self.level() instanceof ServerLevel level) {
            AiSocial.relationTo(level, self.getUUID(), other.getUUID()).ifPresent(r -> parts.add(otherName + "'s " + r));
        }
        AiMemorySavedData.get(server).opinions(self.getUUID()).stream().filter(o -> o.target().equals(other.getUUID()))
                .findFirst().ifPresent(o -> parts.add("feels about " + otherName + ": warmth " + o.warmth() + ", trust "
                        + o.trust() + ", respect " + o.respect() + (o.cause().isEmpty() ? "" : " (because " + o.cause() + ")")));
        long day = AffectionMath.dayOf(server.overworld().getGameTime());
        AiMemorySavedData.get(server).bereavements(self.getUUID(), day).stream().findFirst()
                .ifPresent(loss -> parts.add("grieving their " + loss.relation() + " " + loss.name()));
        sb.append("- ").append(name).append(": ").append(String.join("; ", parts)).append(".\n");
    }

    private static String body(McaChatAi.Settings settings, String prompt) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", prompt);
        messages.add(user);
        body.add("messages", messages);
        if (McaConversationsConfig.aiRequestJsonMode()) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        }
        return body.toString();
    }

    /** The exchange from the model's reply; lines by anyone but the two are dropped. Pure. */
    static Optional<Exchange> parse(String content, String nameA, String nameB) {
        if (content == null) {
            return Optional.empty();
        }
        int open = content.indexOf('{');
        int close = content.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return Optional.empty();
        }
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(content.substring(open, close + 1));
            if (!element.isJsonObject()) {
                return Optional.empty();
            }
            json = element.getAsJsonObject();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (!json.has("lines") || !json.get("lines").isJsonArray()) {
            return Optional.empty();
        }
        List<Line> lines = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("lines")) {
            if (!e.isJsonObject() || lines.size() >= MAX_LINES) {
                continue;
            }
            JsonObject line = e.getAsJsonObject();
            String speaker = line.has("speaker") && line.get("speaker").isJsonPrimitive() ? line.get("speaker").getAsString() : "";
            String text = line.has("text") && line.get("text").isJsonPrimitive()
                    ? AiText.clean(line.get("text").getAsString(), MAX_LINE_CHARS) : "";
            String who = speaker.trim().equalsIgnoreCase(nameA) ? nameA : speaker.trim().equalsIgnoreCase(nameB) ? nameB : "";
            if (!who.isEmpty() && !text.isEmpty()) {
                lines.add(new Line(who, text));
            }
        }
        if (lines.size() < 2) {
            return Optional.empty();
        }
        int warmth = 0;
        try {
            warmth = json.has("warmth") ? Integer.signum(json.get("warmth").getAsInt()) : 0;
        } catch (RuntimeException ignored) {
            // not a number: no change
        }
        String topic = json.has("topic") && json.get("topic").isJsonPrimitive()
                ? AiText.clean(json.get("topic").getAsString(), 80) : "";
        return Optional.of(new Exchange(List.copyOf(lines), warmth, topic));
    }

    // --- playing it out ----------------------------------------------------------------------------------

    private static void play(MinecraftServer server, ServerLevel level, Entity a, String nameA, Entity b, String nameB,
                             Exchange exchange) {
        long now = level.getGameTime();
        int n = exchange.lines().size();
        long end = now + LINE_GAP * (n + 1);
        // They stop and face each other for the whole exchange.
        for (long t = now; t <= end; t += 20) {
            AiTasks.schedule(t, () -> hold(a, b));
        }
        for (int i = 0; i < n; i++) {
            Line line = exchange.lines().get(i);
            Entity speaker = line.speaker().equals(nameA) ? a : b;
            AiTasks.schedule(now + 10 + LINE_GAP * i, () -> say(level, speaker, line));
        }
        AiTasks.schedule(end, () -> {
            TALKING.remove(a.getUUID());
            TALKING.remove(b.getUUID());
            if (exchange.warmth() != 0 && a.isAlive() && b.isAlive()) {
                long day = AffectionMath.dayOf(level.getGameTime());
                String cause = exchange.topic().isEmpty() ? "a chat we had" : "our talk about " + exchange.topic();
                AiMemorySavedData memory = AiMemorySavedData.get(server);
                memory.adjustOpinion(a.getUUID(), b.getUUID(), nameB, "warmth", exchange.warmth(), cause, day);
                memory.adjustOpinion(b.getUUID(), a.getUUID(), nameA, "warmth", exchange.warmth(), cause, day);
            }
        });
    }

    private static void hold(Entity a, Entity b) {
        if (!a.isAlive() || !b.isAlive()) {
            return;
        }
        face(a, b);
        face(b, a);
    }

    private static void face(Entity self, Entity other) {
        if (self instanceof Villager villager) {
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        if (self instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(other);
        }
    }

    private static void say(ServerLevel level, Entity speaker, Line line) {
        if (!speaker.isAlive()) {
            return;
        }
        Component message = Component.literal(line.speaker() + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(line.text()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        for (ServerPlayer player : level.players()) {
            if (player.distanceTo(speaker) <= HEAR_RADIUS) {
                player.displayClientMessage(message, false);
            }
        }
    }

    // --- time heals --------------------------------------------------------------------------------------

    /** Once a day per village: old grievances between neighbours ease a step, now and then. */
    static void drift(MinecraftServer server, List<Entity> residents, long day) {
        AiMemorySavedData memory = AiMemorySavedData.get(server);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Entity resident : residents) {
            for (AiNeighbourOpinion opinion : memory.opinions(resident.getUUID())) {
                if (!AiMediation.feud(opinion) || day - opinion.day() < 5 || random.nextDouble() >= 0.3) {
                    continue;
                }
                String axis = opinion.warmth() <= opinion.trust() && opinion.warmth() <= opinion.respect() ? "warmth"
                        : opinion.trust() <= opinion.respect() ? "trust" : "respect";
                memory.adjustOpinion(resident.getUUID(), opinion.target(), opinion.name(), axis, 1,
                        "time has softened it", day);
            }
        }
    }

    static void reset() {
        LAST_BY_PLAYER.clear();
        LAST_BY_PAIR.clear();
        TALKING.clear();
    }
}
