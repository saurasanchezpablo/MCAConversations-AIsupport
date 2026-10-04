package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.QuestsBridge;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import dev.otectus.mcaconversations.conversation.AgeGroup;
import dev.otectus.mcaconversations.conversation.RelationshipBand;
import dev.otectus.mcaconversations.conversation.RelationshipRoles;
import dev.otectus.mcaconversations.conversation.Relationships;
import dev.otectus.mcaconversations.gossip.GossipEvent;
import dev.otectus.mcaconversations.gossip.GossipSavedData;
import dev.otectus.mcaconversations.state.MemoryIds;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * The villager's social world for one AI turn, read on the server thread: who they are to the player
 * (romance, family), what weighs on them (mourning, a grudge), what is between them (promises, a
 * wish), who is around (bystanders who may chime in, neighbours they have views on), what the village
 * says about the player, and what they could offer (quests, a deeper topic, the way somewhere).
 *
 * <p>Produces both the prompt sections and the {@link AiTurnFacts} the planner validates the reply
 * against, plus name-to-UUID maps so a name the model writes is resolved to exactly the entity that
 * was shown, never to someone else who happens to share it.
 */
final class AiSocial {

    /** Radius within which another villager can hear and chime in. */
    static final double BYSTANDER_RADIUS = 8.0;
    static final int MAX_BYSTANDERS = 3;
    static final int MAX_NEIGHBOURS = 10;
    static final int MAX_VILLAGE_TALK = 3;
    /** Building types too generic to give directions to. */
    static final Set<String> UNNAMED_BUILDINGS = Set.of("house", "big_house", "building", "blocked");
    /**
     * Deeper topics an AI conversation may open, by MCA LongTermMemory unlock id. {@code confided}
     * opens the guarded branches of the authored "secret" topic.
     */
    static final Map<String, String> UNLOCKABLE_TOPICS = Map.of(
            "confided", "you now trust them enough to share your secrets");

    /** Where a place token leads: a village building's centre, or a structure to search for. */
    record Place(String token, String label, Optional<BlockPos> building, Optional<ResourceLocation> structure) {
    }

    /** Everything one turn knows about the villager's social world. */
    record Turn(AiTurnFacts facts, List<AiContextSection> sections, Map<String, UUID> neighbourIds,
                Map<String, UUID> bystanderIds, Map<String, Place> places, List<String> actionOffers,
                Map<String, UUID> helperIds) {
    }

    private AiSocial() {
    }

    static Turn capture(MinecraftServer server, Entity villager, ServerPlayer player, String villagerName,
                        String playerName, AiPolicy policy, long now, long day) {
        ServerLevel level = player.serverLevel();
        AiMemorySavedData data = AiMemorySavedData.get(server);
        UUID villagerId = villager.getUUID();
        AiPairMemory pair = data.peek(villagerId, player.getUUID()).orElse(new AiPairMemory());
        List<AiContextSection> sections = new ArrayList<>();

        // --- who they are to each other ---------------------------------------------------------------
        RelationshipBand band = safeBand(villager, player);
        int hearts = McaCompat.getHearts(player, villager);
        RelationshipRoles roles = safeRoles(villager, player);
        AgeGroup age = McaCompat.ageGroup(villager);
        Optional<UUID> partner = McaCompat.getPartnerUuid(villager);
        boolean partnerIsPlayer = partner.map(player.getUUID()::equals).orElse(false);
        boolean blood = roles.parent() || roles.child() || roles.sibling();
        boolean romanceAllowed = age == AgeGroup.ADULT && !blood && (partner.isEmpty() || partnerIsPlayer);
        List<String> romance = new ArrayList<>();
        if (McaCompat.isMarriedToPlayer(villager, player.getUUID())) {
            romance.add("You are married to " + playerName + ".");
        } else if (McaHandles.isEngagedWith(villager, player.getUUID())) {
            romance.add("You are engaged to " + playerName + ".");
        } else if (McaHandles.isPromisedTo(villager, player.getUUID())) {
            romance.add("You and " + playerName + " are courting (promised).");
        } else if (romanceAllowed) {
            romance.add(hearts >= AiTurnFacts.ROMANCE_MIN_HEARTS
                    ? "Romance with " + playerName + " is possible if it grows naturally; do not rush it."
                    : "You do not know " + playerName + " well enough for romance; deflect any flirting politely.");
        } else {
            romance.add("Romance with " + playerName + " is out of the question"
                    + (age != AgeGroup.ADULT ? " (you are not an adult)" : blood ? " (you are family)"
                    : " (you are with someone else)") + ". Refuse any flirting, kindly but clearly.");
        }
        sections.add(new AiContextSection("Romance", romance));

        // --- what weighs on them ------------------------------------------------------------------------
        List<AiBereavement> losses = data.bereavements(villagerId, day);
        List<String> inner = new ArrayList<>();
        for (AiBereavement loss : losses) {
            inner.add("You are grieving: your " + loss.relation() + " " + loss.name() + " died "
                    + AiContextFormat.daysAgo(day - loss.day()) + ". It is never far from your mind.");
        }
        boolean grudge = pair.grudge(now);
        if (grudge) {
            inner.add("You are hurt and refuse " + playerName + " any favours (no trading, no help, no directions) "
                    + "until they sincerely apologise" + (pair.grudgeReason().isEmpty() ? "" : ". Why: "
                    + pair.grudgeReason()) + ". Be cold, not cruel. If the apology is real, you may forgive.");
        }
        sections.add(new AiContextSection("What weighs on you", inner));

        // --- between them: promises and a wish -------------------------------------------------------------
        List<String> between = new ArrayList<>();
        for (AiPromise promise : pair.promises()) {
            String what = promise.summary().isEmpty() ? describePromise(promise) : promise.summary();
            switch (promise.state()) {
                case PENDING -> between.add(playerName + " promised: " + what
                        + (promise.isVisit() ? "" : " (" + promise.delivered() + "/" + promise.count() + " brought)")
                        + ", due " + due(promise.dueDay() - day) + ".");
                case KEPT -> between.add(playerName + " KEPT a promise " + AiContextFormat.daysAgo(day - promise.settledDay())
                        + ": " + what + ". You were touched.");
                case BROKEN -> between.add(playerName + " BROKE a promise " + AiContextFormat.daysAgo(day - promise.settledDay())
                        + ": " + what + ". You are disappointed and may bring it up.");
            }
        }
        Optional<AiWish> wish = pair.wish(day);
        wish.ifPresent(w -> between.add("You mentioned you would love " + AiContextFormat.words(w.item().replace("#", ""))
                + (w.summary().isEmpty() ? "" : " (" + w.summary() + ")") + "; you are still hoping."));
        sections.add(new AiContextSection("Between you and " + playerName, between));

        // --- opinions of neighbours --------------------------------------------------------------------------
        Map<String, UUID> neighbourIds = neighbours(level, villager, data);
        List<String> views = new ArrayList<>();
        for (AiNeighbourOpinion opinion : data.opinions(villagerId)) {
            List<String> axes = new ArrayList<>();
            for (String axis : List.of("warmth", "trust", "respect")) {
                int value = opinion.value(axis);
                if (value != 0) {
                    axes.add(axis + " " + (value > 0 ? "+" : "") + value);
                }
            }
            views.add(opinion.name() + ": " + String.join(", ", axes)
                    + (opinion.cause().isEmpty() ? "" : " (because " + opinion.cause() + ")"));
        }
        if (!neighbourIds.isEmpty()) {
            views.add("Neighbours you know: " + String.join(", ", neighbourIds.keySet()));
        }
        List<String> feuds = AiMediation.feuds(data, villagerId, neighbourIds);
        if (!feuds.isEmpty()) {
            views.add("You have fallen out with: " + String.join(", ", feuds) + ". If " + playerName + " tries to make "
                    + "peace between you, take it seriously: you might be persuaded, or you might not be ready yet.");
        }
        views.addAll(AiMediation.promptLines(server, villagerId, day));
        sections.add(new AiContextSection("Your views of neighbours", views));

        // --- village life: festivals, markets, funerals, weddings, quarrels -----------------------------------
        List<String> villageLife = new ArrayList<>(AiVillageEvents.promptLines(server, villager, player));
        villageLife.addAll(AiPolitics.promptLines(server, villager, player));
        villageLife.addAll(AiThreats.promptLines(server, villager, player));
        sections.add(new AiContextSection("Village life", villageLife));

        // --- their own life lately: needs, skills, childhood, a date, secrets ----------------------------------
        List<String> ownLife = new ArrayList<>();
        ownLife.addAll(AiNeeds.promptLines(level, villager, pair, playerName));
        ownLife.addAll(AiSkills.promptLines(server, villager, playerName));
        ownLife.addAll(AiChildhood.promptLines(server, villager, player));
        ownLife.addAll(AiDates.promptLines(server, villagerId, player));
        ownLife.addAll(AiBag.promptLines(server, villager, player));
        ownLife.addAll(AiSecrets.promptLines(pair, playerName, band.isAtLeast(RelationshipBand.FRIEND) || roles.any()));
        sections.add(new AiContextSection("Your life lately", ownLife));
        sections.add(new AiContextSection("What you can see of " + playerName,
                AiAppearance.lines(AiAppearance.of(player), playerName)));

        // --- what the village says about the player ---------------------------------------------------------
        sections.add(new AiContextSection("What the village says about " + playerName,
                villageTalk(server, villager, player, now)));

        // --- who is around ----------------------------------------------------------------------------------
        Map<String, UUID> bystanderIds = new LinkedHashMap<>();
        List<String> around = new ArrayList<>();
        for (Entity other : bystanders(level, villager, player)) {
            String name = McaCompat.getVillagerName(other).orElse(null);
            if (name == null || name.isBlank() || bystanderIds.containsKey(name)) {
                continue;
            }
            bystanderIds.put(name, other.getUUID());
            around.add(describeBystander(server, villager, other, player, name, playerName, level, day));
        }
        sections.add(new AiContextSection("Others close enough to hear", around));

        // --- what they could offer ------------------------------------------------------------------------
        Set<String> quests = new HashSet<>();
        if (policy.gameplayEffects() && QuestsBridge.isAvailable() && QuestsBridge.queries() != null) {
            try {
                quests.addAll(QuestsBridge.queries().eligibleOfferIds(player, villager));
            } catch (Throwable ignored) {
                // MCA: Quests unavailable this turn: no work to bring up.
            }
        }
        Set<String> topics = new HashSet<>();
        if (band.isAtLeast(RelationshipBand.FRIEND)) {
            for (String topic : UNLOCKABLE_TOPICS.keySet()) {
                if (!McaCompat.hasMemory(villager, MemoryIds.playerScoped(MemoryIds.unlock(topic), player.getUUID()))) {
                    topics.add(topic);
                }
            }
        }
        Map<String, Place> places = places(level, villager, band, pair, day);
        boolean canDate = romanceAllowed && hearts >= AiTurnFacts.ROMANCE_MIN_HEARTS
                && !AiDates.pending(server, villagerId, player.getUUID());
        AiActionContext.Snapshot actions = AiActionContext.capture(level, villager, player, villagerName, playerName, band,
                roles, grudge, places, canDate);
        AiTurnFacts.Life life = new AiTurnFacts.Life(Set.copyOf(AiPolitics.candidates(server, villager)),
                band.isAtLeast(RelationshipBand.ACQUAINTANCE) && !grudge ? AiSkills.teachable(villager, player) : Set.of(),
                AiDates.onDate(server, villagerId, player.getUUID()));
        sections.addAll(actions.sections());

        AiTurnFacts facts = new AiTurnFacts(band, hearts, romanceAllowed, !losses.isEmpty(), grudge, quests, topics,
                places.keySet(), neighbourIds.keySet(), bystanderIds.keySet(), (int) pair.openPromises(),
                wish.isPresent(), actions.actions(), actions.chores(), actions.helpers().keySet(), Set.copyOf(feuds), life);
        return new Turn(facts, sections, neighbourIds, bystanderIds, places, actions.offers(), actions.helpers());
    }

    /** The menu of things the model may name in effects; the schema text lists exactly these. */
    static List<String> offers(Turn turn) {
        List<String> out = new ArrayList<>(turn.actionOffers());
        if (!turn.facts().offeredQuests().isEmpty()) {
            out.add("{\"type\": \"offer_quest\", \"quest\": one of " + quoted(turn.facts().offeredQuests())
                    + "} when real work you need done fits the conversation");
        }
        for (String topic : turn.facts().offeredTopics()) {
            out.add("{\"type\": \"unlock_topic\", \"topic\": \"" + topic + "\"} when " + UNLOCKABLE_TOPICS.get(topic));
        }
        if (!turn.places().isEmpty()) {
            List<String> labels = turn.places().values().stream()
                    .map(p -> "\"" + p.token() + "\" (" + p.label() + ")").toList();
            out.add("{\"type\": \"directions\", \"place\": one of " + String.join(", ", labels)
                    + "} when asked the way; the game adds the exact directions after your line");
        }
        AiTurnFacts.Life life = turn.facts().life();
        if (!life.candidates().isEmpty()) {
            out.add("{\"type\": \"vote\", \"for\": one of " + quoted(life.candidates())
                    + "} (a candidate's name) when the player gives you a reason you accept to vote for that candidate");
        }
        if (!life.recipes().isEmpty()) {
            out.add("{\"type\": \"teach_recipe\", \"item\": one of " + quoted(life.recipes()) + "} when the player "
                    + "asks how to make it, or you want to share something of your craft; the game teaches them the recipe");
        }
        if (turn.facts().atLeast(RelationshipBand.ACQUAINTANCE)) {
            out.add("{\"type\": \"teach\", \"task\": \"chop|harvest|hunt|fish|mine\"} when the player genuinely shows "
                    + "or explains how to do that work better, and you take it in");
        }
        if (!turn.neighbourIds().isEmpty()) {
            out.add("{\"type\": \"secret_told\", \"about\": a neighbour's exact name, \"summary\": \"what you heard\"} "
                    + "when the player repeats to you something that neighbour told them in confidence");
        }
        if (!turn.facts().feuds().isEmpty()) {
            out.add("{\"type\": \"reconcile\", \"with\": one of " + quoted(turn.neighbourIds().keySet().stream()
                    .filter(n -> AiTurnFacts.contains(turn.facts().feuds(), n)).toList()) + "} when " 
                    + "the player sincerely talks you into making peace with that neighbour and you agree to let it go");
        }
        if (!turn.neighbourIds().isEmpty()) {
            out.add("{\"type\": \"opinion\", \"about\": a neighbour's exact name, \"axis\": \"warmth|trust|respect\", "
                    + "\"direction\": \"up|down\", \"cause\": \"short reason\"} when what was said changes how you see them");
        }
        return out;
    }

    private static String quoted(java.util.Collection<String> values) {
        return values.stream().sorted().map(v -> "\"" + v + "\"").collect(java.util.stream.Collectors.joining(", "));
    }

    // ---------------------------------------------------------------------------------------------

    private static String describePromise(AiPromise promise) {
        return promise.isVisit() ? "to come back" : "to bring " + promise.count() + " "
                + AiContextFormat.words(promise.item().replace("#", ""));
    }

    private static String due(long daysLeft) {
        if (daysLeft < 0) {
            return "overdue by " + (-daysLeft) + " day" + (daysLeft == -1 ? "" : "s");
        }
        return daysLeft == 0 ? "today" : daysLeft == 1 ? "tomorrow" : "in " + daysLeft + " days";
    }

    /** Village residents with a unique name, preferring those this villager has views on and family. */
    private static Map<String, UUID> neighbours(ServerLevel level, Entity villager, AiMemorySavedData data) {
        OptionalInt villageId = McaCompat.getHomeVillageId(villager);
        Map<String, UUID> out = new LinkedHashMap<>();
        if (villageId.isEmpty()) {
            return out;
        }
        Map<UUID, String> residents = McaCompat.villageResidentNames(level, villageId.getAsInt());
        Map<String, Integer> nameCounts = new HashMap<>();
        residents.values().forEach(n -> nameCounts.merge(n.toLowerCase(Locale.ROOT), 1, Integer::sum));
        List<UUID> order = new ArrayList<>();
        data.opinions(villager.getUUID()).forEach(o -> order.add(o.target()));
        McaCompat.getPartnerUuid(villager).ifPresent(order::add);
        order.addAll(McaCompat.getParents(level, villager.getUUID()));
        order.addAll(McaCompat.getSiblings(level, villager.getUUID()));
        order.addAll(McaCompat.getChildren(level, villager.getUUID()));
        order.addAll(residents.keySet().stream().sorted().toList());
        for (UUID id : order) {
            String name = residents.get(id);
            if (id.equals(villager.getUUID()) || name == null || name.isBlank()
                    || nameCounts.getOrDefault(name.toLowerCase(Locale.ROOT), 0) != 1 || out.containsValue(id)) {
                continue; // an ambiguous name is never offered: it could resolve to the wrong person
            }
            out.put(name, id);
            if (out.size() >= MAX_NEIGHBOURS) {
                break;
            }
        }
        return out;
    }

    private static List<Entity> bystanders(ServerLevel level, Entity villager, ServerPlayer player) {
        return level.getEntities(player, player.getBoundingBox().inflate(BYSTANDER_RADIUS),
                        e -> e != villager && e.isAlive() && McaCompat.isMcaVillager(e)
                                && (McaCompat.ageGroup(e) == AgeGroup.ADULT || McaCompat.ageGroup(e) == AgeGroup.TEEN))
                .stream()
                .sorted((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)))
                .limit(MAX_BYSTANDERS)
                .toList();
    }

    private static String describeBystander(MinecraftServer server, Entity speaker, Entity other, ServerPlayer player,
                                            String name, String playerName, ServerLevel level, long day) {
        List<String> parts = new ArrayList<>();
        McaCompat.getProfessionId(other).map(AiContextFormat::words).filter(p -> !p.isEmpty() && !p.equals("none"))
                .ifPresent(parts::add);
        McaCompat.getPersonality(other).map(AiContextFormat::words).ifPresent(parts::add);
        relationTo(level, speaker.getUUID(), other.getUUID()).ifPresent(r -> parts.add("your " + r));
        RelationshipBand band = safeBand(other, player);
        if (band != null) {
            parts.add("to " + playerName + ": " + band.key());
        }
        AiMemorySavedData.get(server).recall(other.getUUID(), player.getUUID(), day, 1)
                .forEach(m -> parts.add("remembers: \"" + m.text() + "\""));
        return name + " (" + String.join("; ", parts) + ")";
    }

    /** How {@code other} is related to {@code self}, from MCA's family tree. */
    static Optional<String> relationTo(ServerLevel level, UUID self, UUID other) {
        if (McaCompat.getPartnerFromTree(level, self).map(other::equals).orElse(false)) {
            return Optional.of("partner");
        }
        if (McaCompat.getParents(level, self).contains(other)) {
            return Optional.of("parent");
        }
        if (McaCompat.getChildren(level, self).contains(other)) {
            return Optional.of("child");
        }
        if (McaCompat.getSiblings(level, self).contains(other)) {
            return Optional.of("sibling");
        }
        return Optional.empty();
    }

    /** Stories the village tells about this player (AI-conversation gossip), heard from others. */
    private static List<String> villageTalk(MinecraftServer server, Entity villager, ServerPlayer player, long now) {
        List<String> out = new ArrayList<>();
        OptionalInt villageId = McaCompat.getHomeVillageId(villager);
        if (villageId.isEmpty() || !McaConversationsConfig.COMMON.enableGossip.get()) {
            return out;
        }
        long retention = McaConversationsConfig.COMMON.gossipRetentionDays.get() * 24000L;
        List<GossipEvent> stories = GossipSavedData.get(server).log().events().stream()
                .filter(e -> e.villageId() == villageId.getAsInt() && e.type().aboutListener())
                .filter(e -> player.getUUID().equals(e.aUuid()) && !e.involves(villager.getUUID()))
                .filter(e -> now - e.created() <= retention)
                .sorted((a, b) -> Long.compare(b.created(), a.created()))
                .limit(MAX_VILLAGE_TALK)
                .toList();
        for (GossipEvent story : stories) {
            String kind = story.type().name().endsWith("KINDNESS") ? "was kind to" : "was cruel to";
            String detail = story.attributes().getOrDefault("story", "");
            out.add("You heard " + story.aName() + " " + kind + " " + story.bName()
                    + (detail.isEmpty() ? "" : ": \"" + detail + "\"") + " (" + AiContextFormat.daysAgo((now - story.created()) / 24000L) + ")");
        }
        return out;
    }

    /** Village buildings always; rumoured structures only to friends, and once a day. */
    private static Map<String, Place> places(ServerLevel level, Entity villager, RelationshipBand band,
                                             AiPairMemory pair, long day) {
        Map<String, Place> out = new LinkedHashMap<>();
        OptionalInt villageId = McaCompat.getHomeVillageId(villager);
        if (villageId.isPresent()) {
            McaHandles.villageBuildingCentres(level, villageId.getAsInt(), villager.blockPosition()).forEach((type, pos) -> {
                if (!UNNAMED_BUILDINGS.contains(type) && type.matches("[a-z0-9_]+")) {
                    out.put(type, new Place(type, AiContextFormat.words(type), Optional.of(pos), Optional.empty()));
                }
            });
        }
        if (band.isAtLeast(RelationshipBand.FRIEND) && pair.lastDirectionsDayIsNot(day)) {
            for (String id : McaHandles.structuresInRumors()) {
                ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
                if (rl != null && !id.startsWith("#") && out.size() < 16) {
                    String token = rl.toString();
                    out.put(token, new Place(token, AiContextFormat.words(rl.getPath()), Optional.empty(), Optional.of(rl)));
                }
            }
        }
        return out;
    }

    private static RelationshipBand safeBand(Entity villager, ServerPlayer player) {
        try {
            return Relationships.bandOf(villager, player);
        } catch (Throwable t) {
            return RelationshipBand.STRANGER;
        }
    }

    private static RelationshipRoles safeRoles(Entity villager, ServerPlayer player) {
        try {
            return Relationships.rolesOf(villager, player);
        } catch (Throwable t) {
            return new RelationshipRoles(false, false, false, false);
        }
    }
}
