package dev.otectus.mcaconversations.ai;

/** Something the villager should remember about this exchange, as the model proposed it. */
public record AiMemoryNote(String text, AiImportance importance) {
}
