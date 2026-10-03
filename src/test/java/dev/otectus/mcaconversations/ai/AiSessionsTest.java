package dev.otectus.mcaconversations.ai;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSessionsTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID PLAYER_A = UUID.randomUUID();
    private static final UUID PLAYER_B = UUID.randomUUID();

    @Test
    void oneTurnInFlightPerPair() {
        AiSessions sessions = new AiSessions();
        assertEquals(AiSessions.Admission.ADMITTED, sessions.admit(ALICE, PLAYER_A, 100, 0, 6000));
        assertEquals(AiSessions.Admission.IN_FLIGHT, sessions.admit(ALICE, PLAYER_A, 200, 0, 6000));
        // Another player with the same villager, and the same player with another villager, are independent.
        assertEquals(AiSessions.Admission.ADMITTED, sessions.admit(ALICE, PLAYER_B, 200, 0, 6000));
        assertEquals(AiSessions.Admission.ADMITTED, sessions.admit(BOB, PLAYER_A, 200, 0, 6000));
        sessions.finish(ALICE, PLAYER_A, 250);
        assertEquals(AiSessions.Admission.ADMITTED, sessions.admit(ALICE, PLAYER_A, 300, 0, 6000));
    }

    @Test
    void thePlayerCooldownThrottlesRequests() {
        AiSessions sessions = new AiSessions();
        sessions.admit(ALICE, PLAYER_A, 100, 40, 6000);
        sessions.finish(ALICE, PLAYER_A, 110);
        assertEquals(AiSessions.Admission.COOLDOWN, sessions.admit(BOB, PLAYER_A, 120, 40, 6000));
        assertEquals(AiSessions.Admission.ADMITTED, sessions.admit(BOB, PLAYER_A, 140, 40, 6000));
    }

    @Test
    void anIdleConversationStartsOverWithAFreshBudget() {
        AiSessions sessions = new AiSessions();
        sessions.admit(ALICE, PLAYER_A, 0, 0, 1000);
        AiSessions.Session session = sessions.session(ALICE, PLAYER_A);
        session.recordApplied(3);
        session.recordApplied(-2);
        session.recordExchange("hi", "hello");
        sessions.finish(ALICE, PLAYER_A, 10);

        sessions.admit(ALICE, PLAYER_A, 500, 0, 1000);
        assertEquals(3, session.positiveApplied(), "within the idle window the budget carries over");
        assertEquals(2, session.negativeApplied());
        sessions.finish(ALICE, PLAYER_A, 510);

        sessions.admit(ALICE, PLAYER_A, 5000, 0, 1000);
        assertEquals(0, session.positiveApplied());
        assertEquals(0, session.negativeApplied());
        assertTrue(session.transcript().isEmpty());
    }

    @Test
    void theTranscriptIsBounded() {
        AiSessions.Session session = new AiSessions().session(ALICE, PLAYER_A);
        for (int i = 0; i < 50; i++) {
            session.recordExchange("question " + i + " " + "x".repeat(200), "answer " + i + " " + "y".repeat(200));
        }
        assertTrue(session.transcript().size() <= AiSessions.MAX_TRANSCRIPT_LINES);
        assertTrue(session.transcript().stream().mapToInt(l -> l.text().length()).sum() <= AiSessions.MAX_TRANSCRIPT_CHARS);
        assertTrue(session.transcript().get(session.transcript().size() - 1).text().startsWith("answer 49"));
    }

    @Test
    void logoutAndDeathForgetOnlyTheirOwnSessions() {
        AiSessions sessions = new AiSessions();
        sessions.admit(ALICE, PLAYER_A, 0, 0, 1000);
        sessions.admit(ALICE, PLAYER_B, 0, 0, 1000);
        sessions.admit(BOB, PLAYER_B, 0, 0, 1000);
        sessions.removePlayer(PLAYER_A);
        assertEquals(2, sessions.size());
        sessions.removeVillager(ALICE);
        assertEquals(1, sessions.size());
        assertTrue(sessions.inFlight(BOB, PLAYER_B));
    }
}
