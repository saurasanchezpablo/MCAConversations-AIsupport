package dev.otectus.mcaconversations.ai;

import java.util.List;
import java.util.UUID;

/**
 * Everything one request is built from, captured on the server thread and immutable afterwards, so
 * the request can be built and sent on any thread without touching the world.
 *
 * @param model              MCA's configured model id
 * @param systemPrompt       MCA's configured system prompt; blank means MCA's default (or none in-house)
 * @param inHouse            the endpoint is MCA's hosted service
 * @param sessionTags        include MCA's {@code [world_id..][player_id..][character_id..]} tags
 * @param longTermMemoryTag  MCA's {@code [use_memory:true]} hosted-memory tag
 * @param sharedMemoryTag    MCA's {@code [shared_memory:true]} tag
 * @param language           MCA's fallback language hint, or null
 * @param jsonMode           ask the endpoint for {@code response_format: json_object}
 * @param worldSeed          the world seed, only used inside session tags (as MCA does)
 * @param mcaDescription     MCA's own prompt-module description of the villager
 * @param editedContext      MCA's operator-edited background/player/village context
 * @param safetyRule         MCA's age/family rule for this pair, or blank
 * @param sections           this mod's structured context, already filtered to known values
 * @param memories           what the villager remembers of this player, most important first
 * @param today              the current Minecraft day, to date the memories
 * @param commands           MCA's currently valid commands; empty when tools are off
 * @param transcript         the current conversation so far
 * @param playerMessage      what the player just said
 * @param offers             what this villager could offer right now, as schema lines (quests, topics, places,
 *                           neighbours); the model may only name these
 * @param bystanders         true when someone is close enough to chime in
 */
public record AiPromptInput(String model, String systemPrompt, boolean inHouse, boolean sessionTags,
                            boolean longTermMemoryTag, boolean sharedMemoryTag, String language, boolean jsonMode,
                            long worldSeed, UUID playerId, UUID villagerId, String playerName, String villagerName,
                            String mcaDescription, String editedContext, String safetyRule,
                            List<AiContextSection> sections, List<AiMemory> memories, long today,
                            List<CommandOption> commands, List<AiSessions.Line> transcript, String playerMessage,
                            List<String> offers, boolean bystanders) {

    public AiPromptInput {
        sections = sections == null ? List.of() : List.copyOf(sections);
        memories = memories == null ? List.of() : List.copyOf(memories);
        commands = commands == null ? List.of() : List.copyOf(commands);
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
        offers = offers == null ? List.of() : List.copyOf(offers);
    }

    /** Without the social layer: no offers, nobody around. */
    public AiPromptInput(String model, String systemPrompt, boolean inHouse, boolean sessionTags,
                         boolean longTermMemoryTag, boolean sharedMemoryTag, String language, boolean jsonMode,
                         long worldSeed, UUID playerId, UUID villagerId, String playerName, String villagerName,
                         String mcaDescription, String editedContext, String safetyRule,
                         List<AiContextSection> sections, List<AiMemory> memories, long today,
                         List<CommandOption> commands, List<AiSessions.Line> transcript, String playerMessage) {
        this(model, systemPrompt, inHouse, sessionTags, longTermMemoryTag, sharedMemoryTag, language, jsonMode,
                worldSeed, playerId, villagerId, playerName, villagerName, mcaDescription, editedContext, safetyRule,
                sections, memories, today, commands, transcript, playerMessage, List.of(), false);
    }

    /** An MCA chat-AI command the model may name in {@code optionalCommand}. */
    public record CommandOption(String id, String description) {
    }
}
