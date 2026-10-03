package dev.otectus.mcaconversations.client.voice;

import dev.otectus.mcaconversations.voice.VoiceDirection;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Directions that arrived before their line. MCA delivers a villager's line only once the villager has
 * walked over to the listener, so a direction may wait a while. A direction for an exact line is matched
 * by its text; one with no text applies to the villager's next line. Client thread only.
 */
final class PendingDirections {

    /** A direction older than this is stale: the line was never delivered or was delivered elsewhere. */
    static final long MAX_AGE_MILLIS = 120_000;
    static final int MAX_PER_VILLAGER = 8;

    private record Entry(VoiceDirection direction, long receivedAt) {
    }

    private final Map<UUID, Deque<Entry>> byVillager = new HashMap<>();

    void add(VoiceDirection direction, long now) {
        Deque<Entry> queue = byVillager.computeIfAbsent(direction.villager(), k -> new ArrayDeque<>());
        queue.addLast(new Entry(direction, now));
        while (queue.size() > MAX_PER_VILLAGER) {
            queue.removeFirst();
        }
    }

    /** The direction for this line from this villager, removed once used. */
    Optional<VoiceDirection> take(UUID villager, String line, long now) {
        Deque<Entry> queue = byVillager.get(villager);
        if (queue == null) {
            return Optional.empty();
        }
        queue.removeIf(e -> now - e.receivedAt > MAX_AGE_MILLIS);
        String wanted = normalise(line);
        for (Iterator<Entry> it = queue.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (!entry.direction.anyLine() && normalise(entry.direction.text()).equals(wanted)) {
                it.remove();
                return Optional.of(entry.direction);
            }
        }
        for (Iterator<Entry> it = queue.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (entry.direction.anyLine()) {
                it.remove();
                return Optional.of(entry.direction);
            }
        }
        return Optional.empty();
    }

    void clear() {
        byVillager.clear();
    }

    static String normalise(String text) {
        StringBuilder out = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(out::appendCodePoint);
        return out.toString();
    }
}
