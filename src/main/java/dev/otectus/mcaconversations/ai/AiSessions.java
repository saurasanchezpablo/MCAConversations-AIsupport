package dev.otectus.mcaconversations.ai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The transient half of AI conversations, per villager-player pair: the last few lines of the
 * current conversation, whether a request is in flight, and how many hearts this conversation has
 * already paid. Nothing here is saved. A conversation ends after a configurable idle gap, and what
 * mattered in it has by then been distilled into an {@link AiMemory}.
 *
 * <p>Pure and server-thread confined: every caller reaches it from the server thread (MCA's
 * pre-7.7.1 pool-thread call hops there first), so it needs no locking.
 */
public final class AiSessions {

    /** Most lines (both speakers) replayed to the model as the conversation so far. */
    static final int MAX_TRANSCRIPT_LINES = 8;
    /** Most characters of transcript replayed, so a long conversation cannot inflate every request. */
    static final int MAX_TRANSCRIPT_CHARS = 2000;

    public enum Admission {
        /** The turn may go ahead; the pair is now marked in flight. */
        ADMITTED,
        /** The previous message to this villager has not been answered yet. */
        IN_FLIGHT,
        /** This player sent an AI message too recently. */
        COOLDOWN
    }

    /** One line of the current conversation. */
    public record Line(boolean fromPlayer, String text) {
    }

    /** One pair's live conversation. */
    public static final class Session {
        private final Deque<Line> transcript = new ArrayDeque<>();
        private long lastActivity;
        private boolean inFlight;
        /** Which turn is in flight: a late reply of an older turn may not end a newer one. */
        private long turn;
        private int positiveApplied;
        private int negativeApplied;
        private long serial;

        public List<Line> transcript() {
            return List.copyOf(transcript);
        }

        public int positiveApplied() {
            return positiveApplied;
        }

        public int negativeApplied() {
            return negativeApplied;
        }

        /** Books a granted heart change against this conversation's budget. */
        public void recordApplied(int granted) {
            if (granted > 0) {
                positiveApplied += granted;
            } else if (granted < 0) {
                negativeApplied += -granted;
            }
        }

        /** Appends one exchange, then trims the oldest lines past the line and character bounds. */
        public void recordExchange(String playerLine, String villagerLine) {
            transcript.addLast(new Line(true, playerLine));
            transcript.addLast(new Line(false, villagerLine));
            int chars = transcript.stream().mapToInt(line -> line.text().length()).sum();
            while (transcript.size() > MAX_TRANSCRIPT_LINES || (chars > MAX_TRANSCRIPT_CHARS && transcript.size() > 2)) {
                chars -= transcript.removeFirst().text().length();
            }
        }

        /** The villager spoke first: their line opens the transcript with nothing before it. */
        public void recordOpening(String villagerLine) {
            transcript.addLast(new Line(false, villagerLine));
            while (transcript.size() > MAX_TRANSCRIPT_LINES) {
                transcript.removeFirst();
            }
        }

        /** A per-pair turn counter, unique within this conversation; part of the heart transaction id. */
        public long nextSerial() {
            return ++serial;
        }

        void reset() {
            transcript.clear();
            positiveApplied = 0;
            negativeApplied = 0;
        }
    }

    private final Map<AiMemoryStore.PairKey, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastTurnByPlayer = new HashMap<>();
    private long turns;

    /**
     * Decides whether a new turn may start now, and if so marks the pair in flight. A conversation
     * idle for longer than {@code idleTicks} starts over: empty transcript, fresh budget.
     */
    public Admission admit(UUID villager, UUID player, long now, int cooldownTicks, int idleTicks) {
        AiMemoryStore.PairKey key = new AiMemoryStore.PairKey(villager, player);
        Session session = sessions.computeIfAbsent(key, k -> new Session());
        if (session.inFlight) {
            return Admission.IN_FLIGHT;
        }
        Long last = lastTurnByPlayer.get(player);
        if (last != null && now >= last && now - last < cooldownTicks) {
            return Admission.COOLDOWN;
        }
        if (now - session.lastActivity > idleTicks || now < session.lastActivity) {
            session.reset();
        }
        session.inFlight = true;
        session.turn = ++turns;
        session.lastActivity = now;
        lastTurnByPlayer.put(player, now);
        return Admission.ADMITTED;
    }

    /** The pair's live session, created if needed. */
    public Session session(UUID villager, UUID player) {
        return sessions.computeIfAbsent(new AiMemoryStore.PairKey(villager, player), k -> new Session());
    }

    /** Ends a turn, successful or not; the pair can take the next message. */
    public void finish(UUID villager, UUID player, long now) {
        Session session = sessions.get(new AiMemoryStore.PairKey(villager, player));
        if (session != null) {
            session.inFlight = false;
            session.lastActivity = Math.max(session.lastActivity, now);
        }
    }

    /** The turn last admitted for this pair (0 when none), to hand back to {@link #finish(UUID, UUID, long, long)}. */
    public long currentTurn(UUID villager, UUID player) {
        Session session = sessions.get(new AiMemoryStore.PairKey(villager, player));
        return session == null ? 0 : session.turn;
    }

    /** Whether {@code turn} is still this pair's live turn (the player did not leave and come back meanwhile). */
    public boolean isCurrent(UUID villager, UUID player, long turn) {
        Session session = sessions.get(new AiMemoryStore.PairKey(villager, player));
        return session != null && session.inFlight && session.turn == turn;
    }

    /** Ends turn {@code turn}; a stale call for an older turn leaves a newer one in flight. */
    public void finish(UUID villager, UUID player, long now, long turn) {
        Session session = sessions.get(new AiMemoryStore.PairKey(villager, player));
        if (session != null && session.turn == turn) {
            session.inFlight = false;
            session.lastActivity = Math.max(session.lastActivity, now);
        }
    }

    public boolean inFlight(UUID villager, UUID player) {
        Session session = sessions.get(new AiMemoryStore.PairKey(villager, player));
        return session != null && session.inFlight;
    }

    public void removeVillager(UUID villager) {
        sessions.keySet().removeIf(key -> key.villager().equals(villager));
    }

    public void removePlayer(UUID player) {
        sessions.keySet().removeIf(key -> key.player().equals(player));
        lastTurnByPlayer.remove(player);
    }

    /** Drops sessions idle past {@code idleTicks} that are not waiting on a reply. */
    public void expire(long now, int idleTicks) {
        sessions.values().removeIf(session -> !session.inFlight && now - session.lastActivity > idleTicks);
    }

    public void clear() {
        sessions.clear();
        lastTurnByPlayer.clear();
    }

    public int size() {
        return sessions.size();
    }
}
