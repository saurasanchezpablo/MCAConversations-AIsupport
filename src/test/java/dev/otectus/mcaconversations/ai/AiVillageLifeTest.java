package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.conversation.RelationshipBand;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Village events, mediation and cooking: the pure rules. */
class AiVillageLifeTest {

    private static final long DAY = AiVillageEventType.DAY;
    private static final AiPolicy ALL = new AiPolicy(true, true, 0.6, 12);

    private static AiVillageEvent event(AiVillageEventType type, long start, List<String> names, String cause) {
        UUID a = UUID.randomUUID();
        return new AiVillageEvent(7, type, "minecraft:overworld", 3, new BlockPos(10, 64, -5), "inn", start,
                start + type.duration(), a, List.of(a, UUID.randomUUID()), names, cause);
    }

    @Test
    void plannedEventsFollowTheirWeights() {
        assertEquals(AiVillageEventType.FESTIVAL, AiVillageEventType.pickPlanned(0.0));
        assertEquals(AiVillageEventType.FESTIVAL, AiVillageEventType.pickPlanned(0.29));
        assertEquals(AiVillageEventType.MARKET, AiVillageEventType.pickPlanned(0.31));
        assertEquals(AiVillageEventType.HARVEST_FEAST, AiVillageEventType.pickPlanned(0.7));
        assertEquals(AiVillageEventType.QUARREL, AiVillageEventType.pickPlanned(0.99));
        assertEquals(AiVillageEventType.QUARREL, AiVillageEventType.pickPlanned(1.5), "out of range is clamped");
    }

    @Test
    void gatheringsStartAtTheirHourAndAreOverBeforeNight() {
        long morning = 5 * DAY + 500;
        assertEquals(Optional.of(5 * DAY + 10_500), AiVillageEventType.FESTIVAL.startToday(morning, 1_200));
        // The market hour has passed: it starts a little after now instead.
        assertEquals(Optional.of(5 * DAY + 3_200), AiVillageEventType.MARKET.startToday(5 * DAY + 2_000, 1_200));
        // Too late in the day to hold a market at all.
        assertTrue(AiVillageEventType.MARKET.startToday(5 * DAY + 9_000, 1_200).isEmpty());
        assertEquals(6 * DAY + 2_500, AiVillageEventType.WELCOME.startSoon(5 * DAY + 12_000, 2_400), "tomorrow then");
        for (AiVillageEventType type : AiVillageEventType.values()) {
            if (type.gathering()) {
                assertTrue(type.startTime() + type.duration() <= AiVillageEventType.LATEST_END, type + " ends before night");
            }
        }
    }

    @Test
    void eventsAreDescribedWithWhenTheyHappen() {
        AiVillageEvent festival = event(AiVillageEventType.FESTIVAL, 2 * DAY + 10_500, List.of(), "");
        assertEquals("a festival at the inn", festival.describe());
        assertEquals("this evening", festival.when(2 * DAY + 100));
        assertEquals("tomorrow evening", festival.when(DAY + 100));
        assertEquals("right now", festival.when(2 * DAY + 11_000));
        assertEquals("earlier today", festival.when(2 * DAY + 20_000));
        assertEquals("yesterday", festival.when(3 * DAY + 100));

        AiVillageEvent wedding = event(AiVillageEventType.WEDDING, DAY, List.of("Ana", "Luis"), "");
        assertEquals("the wedding of Ana and Luis at the inn", wedding.describe());
        assertEquals("Steve came to our wedding.", AiVillageEvents.honourNote(wedding, "Steve"));
        assertEquals("Steve came to the wedding of Ana and Luis.", AiVillageEvents.guestNote(wedding, "Steve"));

        AiVillageEvent quarrel = event(AiVillageEventType.QUARREL, DAY, List.of("Ana", "Bob"), "a broken fence");
        assertEquals("Ana and Bob falling out over a broken fence", quarrel.describe());
        AiVillageEvent funeral = event(AiVillageEventType.FUNERAL, DAY, List.of("Pedro"), "");
        assertEquals("Steve came to Pedro's funeral. It meant a great deal to me.", AiVillageEvents.honourNote(funeral, "Steve"));
    }

    @Test
    void anEventSurvivesSaving() {
        AiVillageEvent event = event(AiVillageEventType.FUNERAL, 4 * DAY + 3_000, List.of("Pedro"), "");
        UUID player = UUID.randomUUID();
        event.invited.add(player);
        event.attended.add(player);
        event.started = true;
        AiVillageEvent back = AiVillageEvent.fromNbt(event.toNbt()).orElseThrow();
        assertEquals(event.type, back.type);
        assertEquals(event.spot, back.spot);
        assertEquals(event.start, back.start);
        assertEquals(event.end, back.end);
        assertEquals(event.organizer, back.organizer);
        assertEquals(event.subjects, back.subjects);
        assertEquals(event.names, back.names);
        assertEquals(Set.of(player), back.invited);
        assertEquals(Set.of(player), back.attended);
        assertTrue(back.started);
        assertFalse(back.ended);
    }

    @Test
    void aFeudIsMoreBadThanGood() {
        UUID t = UUID.randomUUID();
        assertTrue(AiMediation.feud(new AiNeighbourOpinion(t, "Bob", -2, -1, 0, "a fence", 1)));
        assertFalse(AiMediation.feud(new AiNeighbourOpinion(t, "Bob", -1, 1, 0, "", 1)));
        assertFalse(AiMediation.feud(new AiNeighbourOpinion(t, "Bob", 0, 0, 0, "", 1)));
    }

    private static AiTurnFacts facts(Set<String> feuds, boolean grudge) {
        return new AiTurnFacts(RelationshipBand.FRIEND, 30, false, false, grudge, Set.of(), Set.of(), Set.of(),
                Set.of("Bob", "Mara"), Set.of(), 0, false, Set.of(), Set.of(), Set.of(), feuds);
    }

    private static AiReply reply(AiSentiment sentiment, AiEffect... effects) {
        return new AiReply("line", "", sentiment, 1.0, AiEmotion.HAPPY,
                Optional.empty(), List.of(effects), Optional.empty(), true);
    }

    @Test
    void peaceIsMadeOnlyOverAFeudTheVillagerWasShown() {
        AiEffect.Reconcile withBob = new AiEffect.Reconcile("bob");
        assertEquals(Optional.of("bob"), AiOutcomePlan.of(reply(AiSentiment.POSITIVE, withBob), ALL,
                facts(Set.of("Bob"), false)).reconcile());
        assertTrue(AiOutcomePlan.of(reply(AiSentiment.POSITIVE, new AiEffect.Reconcile("Mara")), ALL,
                facts(Set.of("Bob"), false)).reconcile().isEmpty(), "no feud with Mara");
        assertTrue(AiOutcomePlan.of(reply(AiSentiment.NEGATIVE, withBob), ALL, facts(Set.of("Bob"), false))
                .reconcile().isEmpty(), "not when the player was unkind about it");
        assertTrue(AiOutcomePlan.of(reply(AiSentiment.POSITIVE, withBob), ALL, facts(Set.of("Bob"), true))
                .reconcile().isEmpty(), "not while hurt by the player");
    }

    @Test
    void theParserReadsAReconcileRequest() {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("type", "reconcile");
        json.addProperty("with", "Bob");
        assertEquals(Optional.of(new AiEffect.Reconcile("Bob")), AiReplyParser.parseEffect(json));
        json.remove("with");
        assertTrue(AiReplyParser.parseEffect(json).isEmpty());
    }

    @Test
    void cookingBurnsAFurnacesWorthOfFuelPerItem() {
        assertEquals(8, AiCooking.coverable(1_600), "a piece of coal cooks eight");
        assertEquals(0, AiCooking.coverable(150));
        assertEquals(0, AiCooking.coverable(-5));
        assertEquals(100, AiCooking.coverable(20_000), "a lava bucket cooks a hundred");
    }

    @Test
    void anInvitationShowsAsAnEventBubble() {
        AiInitiative.Facts facts = new AiInitiative.Facts("Steve", RelationshipBand.ACQUAINTANCE, false, 3, 0,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("there is a festival at the inn this evening and you would like Steve to come"));
        AiInitiative.Reason reason = AiInitiative.reason(facts).orElseThrow();
        assertEquals(dev.otectus.mcaconversations.network.VillagerBubblesS2C.EVENT, reason.bubble());
        assertTrue(reason.text().contains("festival"));
    }

    @Test
    void peaceOffersAgeByGameDayNotByTheDayClock() {
        AiVillageLifeSavedData data = new AiVillageLifeSavedData();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        data.offerPeace(new AiVillageLifeSavedData.PeaceOffer(a, b, "Ana", UUID.randomUUID(), 10));
        // Sleeping moved the day clock far ahead of game time: the offer is still fresh.
        data.prune(500 * DAY, 11);
        assertTrue(data.peaceOffer(a, b, 11).isPresent());
        data.prune(500 * DAY, 10 + AiVillageLifeSavedData.PEACE_DAYS + 1);
        assertTrue(data.peaceOffer(a, b, 10).isEmpty(), "lapsed by game day");
    }

    @Test
    void aFullLogDropsEndedEventsBeforeOnesStillToCome() {
        AiVillageLifeSavedData data = new AiVillageLifeSavedData();
        for (int i = 0; i < AiVillageLifeSavedData.MAX_EVENTS; i++) {
            assertTrue(data.add(event(AiVillageEventType.FESTIVAL, i * DAY, List.of(), "")).isEmpty());
        }
        AiVillageEvent ended = data.events().get(40);
        ended.ended = true;
        AiVillageEvent pending = data.events().get(0);
        List<AiVillageEvent> evicted = data.add(event(AiVillageEventType.ELECTION, 999 * DAY, List.of("A", "B"), ""));
        assertEquals(List.of(ended), evicted);
        assertTrue(data.events().contains(pending));
        assertEquals(AiVillageLifeSavedData.MAX_EVENTS, data.events().size());
        // Nothing ended: the oldest goes.
        assertEquals(List.of(pending), data.add(event(AiVillageEventType.MARKET, 1_000 * DAY, List.of(), "")));
    }

    @Test
    void aDeadLeaderOrCandidateLeavesNothingStuck() {
        AiVillageLifeSavedData data = new AiVillageLifeSavedData();
        AiVillageLifeSavedData.Census census = data.census("minecraft:overworld|3");
        UUID leader = UUID.randomUUID();
        UUID rival = UUID.randomUUID();
        census.leader = leader;
        census.leaderName = "Ana";
        census.leaderPlatform = "markets";
        census.nextElectionDay = 40;
        census.candidates.addAll(List.of(leader, rival));
        census.candidateNames.addAll(List.of("Ana", "Bob"));
        census.platforms.addAll(List.of("markets", "harmony"));
        census.votes.put(UUID.randomUUID(), rival);
        data.removeVillager(leader);
        assertEquals(null, census.leader);
        assertEquals("", AiPolitics.platform(census));
        assertEquals(-1, census.nextElectionDay, "a new campaign starts");
        assertFalse(census.electionPending());
        assertTrue(census.candidateNames.isEmpty() && census.platforms.isEmpty() && census.votes.isEmpty());
    }

    @Test
    void quarrelCausesAreTranslatedForPlayers() {
        net.minecraft.network.chat.Component known = AiVillageEvents.cause(AiVillageEvents.QUARREL_CAUSES.get(0));
        assertTrue(known.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals("mcaconversations.ai.event.quarrel.cause.1"));
        net.minecraft.network.chat.Component other = AiVillageEvents.cause("a stolen pie");
        assertEquals("a stolen pie", other.getString());
    }

    @Test
    void theDiaryTitleIsInThePlayersLanguage() {
        assertEquals("My diary", AiDiary.bookTitle("en_us"));
        assertEquals("Meu diário", AiDiary.bookTitle("pt_BR"));
        assertEquals("My diary", AiDiary.bookTitle("xx_yy"), "a language this mod does not ship falls back to English");
        assertEquals("My diary", AiDiary.bookTitle(null));
        assertEquals("My diary", AiDiary.bookTitle("../../evil"));
    }
}
