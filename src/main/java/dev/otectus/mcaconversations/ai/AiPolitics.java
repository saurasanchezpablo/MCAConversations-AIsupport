package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.conversation.AgeGroup;
import dev.otectus.mcaconversations.conversation.DepthClass;
import dev.otectus.mcaconversations.progress.AffectionMath;
import dev.otectus.mcaconversations.progress.ReplayPolicy;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Village politics. A village of five or more chooses a leader: two candidates, each with something
 * they promise, campaign for a couple of days. Then the village gathers and votes.
 *
 * <p>Who a villager votes for follows how they feel about each candidate. A player can talk villagers
 * round ({@code vote}), and a candidate knows who campaigned for them and who against.
 *
 * <p>The leader's promise shapes village life: more markets, more festivals, a village that meets
 * after every attack, fairer prices on market day, or fewer quarrels. The leader remembers who backed
 * them. Elections come round every eight days.
 */
final class AiPolitics {

    static final int MIN_RESIDENTS = 5;
    static final int TERM_DAYS = 8;
    static final int CAMPAIGN_DAYS = 2;

    /** What a candidate can promise, by key, in their words. */
    static final Map<String, String> PLATFORMS = Map.of(
            "markets", "more market days",
            "festivals", "more festivals and feasts",
            "defence", "standing together against the monsters",
            "prices", "fair prices for everyone who trades here",
            "harmony", "settling the neighbours' quarrels");

    private AiPolitics() {
    }

    static String platformWords(String key) {
        return PLATFORMS.getOrDefault(key, "a better village");
    }

    /** Called from the village census: starts a campaign when it is time. */
    static void maintain(MinecraftServer server, ServerLevel level, int villageId, AiVillageLifeSavedData data,
                         AiVillageLifeSavedData.Census census, long now) {
        if (!McaConversationsConfig.aiGameplayEffects() || census.residents.size() < MIN_RESIDENTS || census.electionPending()) {
            return;
        }
        long today = Math.floorDiv(now, AiVillageEventType.DAY);
        if (census.nextElectionDay < 0) {
            census.nextElectionDay = today + CAMPAIGN_DAYS;
            data.changed();
        }
        if (today < census.nextElectionDay - CAMPAIGN_DAYS) {
            return;
        }
        List<Entity> adults = new ArrayList<>(McaCompat.loadedVillageResidents(level, villageId).stream()
                .filter(e -> e.isAlive() && McaCompat.ageGroup(e) == AgeGroup.ADULT && McaCompat.getVillagerName(e).isPresent())
                .toList());
        if (adults.size() < 2) {
            return;
        }
        Collections.shuffle(adults);
        // The leader stands again if they are around.
        adults.stream().filter(e -> e.getUUID().equals(census.leader)).findFirst().ifPresent(l -> {
            adults.remove(l);
            adults.add(0, l);
        });
        Entity a = adults.get(0);
        Entity b = adults.stream().skip(1).filter(e -> !McaCompat.getVillagerName(e).equals(McaCompat.getVillagerName(a)))
                .findFirst().orElse(null);
        if (b == null) {
            return;
        }
        List<String> keys = new ArrayList<>(PLATFORMS.keySet());
        Collections.shuffle(keys);
        census.candidates.clear();
        census.candidateNames.clear();
        census.platforms.clear();
        census.candidates.add(a.getUUID());
        census.candidates.add(b.getUUID());
        census.candidateNames.add(McaCompat.getVillagerName(a).orElse("?"));
        census.candidateNames.add(McaCompat.getVillagerName(b).orElse("?"));
        census.platforms.add(a.getUUID().equals(census.leader) && !census.leaderPlatform.isEmpty()
                ? census.leaderPlatform : keys.get(0));
        census.platforms.add(keys.stream().filter(k -> !k.equals(census.platforms.get(0))).findFirst().orElse(keys.get(1)));
        census.votes.clear();
        census.backers.clear();
        long day = Math.max(census.nextElectionDay, today + 1);
        census.nextElectionDay = day;
        data.changed();
        AiVillageEvents.scheduleElection(server, level, villageId, day * AiVillageEventType.DAY
                + AiVillageEventType.ELECTION.startTime(), List.copyOf(census.candidates), List.copyOf(census.candidateNames));
    }

    /** How a voter leans between the two candidates: their votes, else their opinion of each. */
    static int leaning(AiMemorySavedData memory, AiVillageLifeSavedData.Census census, UUID voter) {
        if (census.candidates.get(0).equals(voter)) {
            return 0;
        }
        if (census.candidates.get(1).equals(voter)) {
            return 1;
        }
        UUID chosen = census.votes.get(voter);
        if (chosen != null) {
            return census.candidates.indexOf(chosen);
        }
        int a = regard(memory, voter, census.candidates.get(0));
        int b = regard(memory, voter, census.candidates.get(1));
        if (a != b) {
            return a > b ? 0 : 1;
        }
        return Math.floorMod((voter.hashCode() ^ census.candidates.get(0).hashCode()), 2);
    }

    private static int regard(AiMemorySavedData memory, UUID voter, UUID candidate) {
        return memory.opinions(voter).stream().filter(o -> o.target().equals(candidate))
                .mapToInt(o -> o.warmth() + o.trust() + o.respect()).sum();
    }

    /** Votes for each candidate. Pure over the given data. */
    static int[] count(AiMemorySavedData memory, AiVillageLifeSavedData.Census census) {
        int[] votes = new int[2];
        for (UUID resident : census.residents) {
            int choice = leaning(memory, census, resident);
            if (choice >= 0) {
                votes[choice]++;
            }
        }
        return votes;
    }

    /** Election day is over: count, announce, and the new leader remembers who stood by them. */
    static void tally(MinecraftServer server, ServerLevel level, AiVillageEvent event) {
        AiVillageLifeSavedData data = AiVillageLifeSavedData.get(server);
        AiVillageLifeSavedData.Census census = data.census(AiVillageEvents.villageKey(level, event.villageId));
        if (!census.electionPending()) {
            return;
        }
        AiMemorySavedData memory = AiMemorySavedData.get(server);
        int[] votes = count(memory, census);
        int winner = votes[0] == votes[1] ? (census.candidates.get(1).equals(census.leader) ? 1 : 0)
                : votes[0] > votes[1] ? 0 : 1;
        int loser = 1 - winner;
        UUID leader = census.candidates.get(winner);
        String leaderName = census.candidateNames.get(winner);
        String loserName = census.candidateNames.get(loser);
        census.leader = leader;
        census.leaderName = leaderName;
        census.leaderPlatform = census.platforms.get(winner);
        long today = Math.floorDiv(server.overworld().getDayTime(), AiVillageEventType.DAY);
        census.leaderSince = today;
        census.nextElectionDay = today + TERM_DAYS;
        Map<UUID, UUID> backers = Map.copyOf(census.backers);
        UUID loserId = census.candidates.get(loser);
        census.candidates.clear();
        census.candidateNames.clear();
        census.platforms.clear();
        census.votes.clear();
        census.backers.clear();
        data.changed();

        Component line = Component.translatable("mcaconversations.ai.event.election.won", leaderName, votes[winner],
                votes[loser], loserName).withStyle(ChatFormatting.GOLD);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(event.spot.getCenter()) <= AiVillageEvents.GATHER_RADIUS * AiVillageEvents.GATHER_RADIUS) {
                player.sendSystemMessage(line);
            }
        }
        long gameNow = level.getGameTime();
        long day = AffectionMath.dayOf(gameNow);
        int cap = Math.max(1, McaConversationsConfig.aiMemoriesPerPair());
        Entity leaderEntity = level.getEntity(leader);
        Entity loserEntity = level.getEntity(loserId);
        for (Map.Entry<UUID, UUID> backer : backers.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(backer.getKey());
            String playerName = AiChildhood.playerName(server, backer.getKey());
            if (backer.getValue().equals(leader)) {
                memory.edit(leader, backer.getKey()).remember(new AiMemoryNote(playerName
                        + " campaigned for me, and I won the election.", AiImportance.HIGH), AiSentiment.STRONGLY_POSITIVE, day, cap);
                if (player != null && leaderEntity != null && McaConversationsConfig.aiRelationshipEffects()) {
                    AiHearts.grant(server, leaderEntity, player, "ai.election", 2, DepthClass.STANDARD, ReplayPolicy.ONCE,
                            0, 0, "ai.election." + event.id + "." + leader, gameNow);
                }
            } else {
                memory.edit(leader, backer.getKey()).remember(new AiMemoryNote(playerName
                        + " campaigned against me in the election.", AiImportance.MEDIUM), AiSentiment.NEGATIVE, day, cap);
                memory.edit(loserId, backer.getKey()).remember(new AiMemoryNote(playerName
                        + " stood by me in the election, even though I lost.", AiImportance.MEDIUM), AiSentiment.POSITIVE, day, cap);
                if (player != null && loserEntity != null && McaConversationsConfig.aiRelationshipEffects()) {
                    AiHearts.grant(server, loserEntity, player, "ai.election", 1, DepthClass.STANDARD, ReplayPolicy.ONCE,
                            0, 0, "ai.election." + event.id + "." + loserId, gameNow);
                }
            }
        }
    }

    /** The player talked a villager into voting for someone. */
    static boolean vote(MinecraftServer server, Entity villager, ServerPlayer player, String candidate, long day) {
        Optional<AiVillageLifeSavedData.Census> found = census(server, villager);
        if (found.isEmpty() || !found.get().electionPending()) {
            return false;
        }
        AiVillageLifeSavedData.Census census = found.get();
        int index = -1;
        for (int i = 0; i < census.candidateNames.size(); i++) {
            if (census.candidateNames.get(i).equalsIgnoreCase(candidate)) {
                index = i;
            }
        }
        if (index < 0 || census.candidates.contains(villager.getUUID())) {
            return false;
        }
        UUID chosen = census.candidates.get(index);
        census.votes.put(villager.getUUID(), chosen);
        census.backers.put(player.getUUID(), chosen);
        AiVillageLifeSavedData.get(server).changed();
        AiMemorySavedData.get(server).edit(villager.getUUID(), player.getUUID()).remember(new AiMemoryNote(
                player.getName().getString() + " talked me into voting for " + census.candidateNames.get(index) + ".",
                AiImportance.LOW), AiSentiment.NEUTRAL, day, Math.max(1, McaConversationsConfig.aiMemoriesPerPair()));
        return true;
    }

    static Optional<AiVillageLifeSavedData.Census> census(MinecraftServer server, Entity villager) {
        OptionalInt village = McaCompat.getHomeVillageId(villager);
        if (village.isEmpty() || !(villager.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return Optional.of(AiVillageLifeSavedData.get(server).census(AiVillageEvents.villageKey(level, village.getAsInt())));
    }

    /** The leader's promise, which shapes what the village plans. */
    static String platform(AiVillageLifeSavedData.Census census) {
        return census.leader == null ? "" : census.leaderPlatform;
    }

    /** Candidate names the player could persuade this villager to vote for. */
    static List<String> candidates(MinecraftServer server, Entity villager) {
        return census(server, villager).filter(AiVillageLifeSavedData.Census::electionPending)
                .filter(c -> !c.candidates.contains(villager.getUUID()))
                .map(c -> List.copyOf(c.candidateNames)).orElse(List.of());
    }

    static List<String> promptLines(MinecraftServer server, Entity villager, ServerPlayer player) {
        List<String> out = new ArrayList<>();
        Optional<AiVillageLifeSavedData.Census> found = census(server, villager);
        if (found.isEmpty()) {
            return out;
        }
        AiVillageLifeSavedData.Census census = found.get();
        long today = Math.floorDiv(server.overworld().getDayTime(), AiVillageEventType.DAY);
        UUID self = villager.getUUID();
        String name = player.getName().getString();
        if (census.leader != null) {
            if (census.leader.equals(self)) {
                out.add("You are the village leader (elected on the promise of " + platformWords(census.leaderPlatform)
                        + "). You take it seriously.");
            } else {
                out.add(census.leaderName + " leads the village, elected " + (today - census.leaderSince <= 0 ? "today"
                        : (today - census.leaderSince) + " days ago") + " on the promise of "
                        + platformWords(census.leaderPlatform) + ".");
            }
        }
        if (census.electionPending()) {
            long in = census.nextElectionDay - today;
            String when = in <= 0 ? "today" : in == 1 ? "tomorrow" : "in " + in + " days";
            out.add("The village chooses its leader " + when + ": " + census.candidateNames.get(0) + " (promising "
                    + platformWords(census.platforms.get(0)) + ") against " + census.candidateNames.get(1) + " (promising "
                    + platformWords(census.platforms.get(1)) + ").");
            int index = census.candidates.indexOf(self);
            if (index >= 0) {
                out.add("You are a candidate! You would like " + name + "'s support; campaign a little, but stay yourself.");
            } else {
                int lean = leaning(AiMemorySavedData.get(server), census, self);
                out.add("You lean towards " + census.candidateNames.get(lean) + (census.votes.containsKey(self)
                        ? " (you have made up your mind)" : "") + ". If " + name + " gives you a good reason, you may change "
                        + "your vote.");
            }
            UUID backed = census.backers.get(player.getUUID());
            if (backed != null) {
                out.add(name + " is campaigning for " + census.candidateNames.get(census.candidates.indexOf(backed)) + ".");
            }
        }
        return out;
    }
}
