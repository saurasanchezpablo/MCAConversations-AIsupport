package dev.otectus.mcaconversations.ai;

/**
 * Something the villager should remember about this exchange, as the model proposed it.
 *
 * @param secret told in confidence: the villager keeps it to themselves and it never becomes village talk
 */
public record AiMemoryNote(String text, AiImportance importance, boolean secret) {

    public AiMemoryNote(String text, AiImportance importance) {
        this(text, importance, false);
    }
}
