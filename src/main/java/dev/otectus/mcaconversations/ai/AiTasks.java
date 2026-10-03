package dev.otectus.mcaconversations.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A tiny server-thread delay queue, so an effect can wait for the villager's line to land first (the
 * quest menu opens after the villager has brought the work up, not over the top of it). Drained
 * every server tick; cleared when the server stops, so nothing crosses into another world.
 */
final class AiTasks {

    private record Task(long due, long seq, Runnable action) {
    }

    private static final PriorityQueue<Task> QUEUE = new PriorityQueue<>(
            (a, b) -> a.due != b.due ? Long.compare(a.due, b.due) : Long.compare(a.seq, b.seq));
    private static long sequence;

    private AiTasks() {
    }

    static void schedule(long dueGameTime, Runnable action) {
        QUEUE.add(new Task(dueGameTime, sequence++, action));
    }

    /** Runs every task due by {@code now}; a task that throws is dropped, never retried. */
    static void drain(long now) {
        List<Task> due = new ArrayList<>();
        while (!QUEUE.isEmpty() && QUEUE.peek().due <= now) {
            due.add(QUEUE.poll());
        }
        for (Task task : due) {
            try {
                task.action.run();
            } catch (Throwable t) {
                dev.otectus.mcaconversations.McaConversations.LOGGER.debug("AI delayed task failed", t);
            }
        }
    }

    static void clear() {
        QUEUE.clear();
    }
}
