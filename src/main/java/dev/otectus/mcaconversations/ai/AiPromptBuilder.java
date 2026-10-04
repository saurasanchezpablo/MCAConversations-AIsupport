package dev.otectus.mcaconversations.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * Builds the chat-completions request for one turn. Pure: the same input gives the same body.
 *
 * <p>The system message keeps MCA's own prompt intact and in MCA's order: session tags, system
 * prompt, operator-edited context, the module description, the age/family rule, the language hint.
 * It then adds two things MCA's prompt cannot have: structured context (relationship, feelings,
 * memories and circumstances, as labelled sections rather than one run-on paragraph), and the reply
 * schema, which extends MCA's {@code {"message", "optionalCommand"}} format instead of replacing it.
 */
public final class AiPromptBuilder {

    /** MCA's default system prompt, verbatim, used when an operator has not set one. */
    static final String MCA_DEFAULT_SYSTEM_PROMPT = "You are a Minecraft villager, fully immersed in their virtual "
            + "world, unaware of its artificial nature. You respond based on your description, your role, and your "
            + "knowledge of the world. You have no knowledge of the real world, and do not realize that you are "
            + "within Minecraft. You are no assistant! You can be sarcastic, funny, or even rude when appropriate.";

    private AiPromptBuilder() {
    }

    /** The JSON request body. */
    public static String body(AiPromptInput in) {
        JsonObject body = new JsonObject();
        body.addProperty("model", in.model());
        JsonArray messages = new JsonArray();
        messages.add(message("system", system(in)));
        for (AiSessions.Line line : in.transcript()) {
            messages.add(message(line.fromPlayer() ? "user" : "assistant", line.text()));
        }
        messages.add(message("user", in.playerMessage()));
        body.add("messages", messages);
        if (in.jsonMode()) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        }
        return body.toString();
    }

    static String system(AiPromptInput in) {
        StringBuilder sb = new StringBuilder(4096);
        if (in.inHouse() || in.sessionTags()) {
            sb.append("[world_id:").append(in.worldSeed()).append(']');
            sb.append("[player_id:").append(in.playerId()).append(']');
            sb.append("[character_id:").append(in.villagerId()).append(']');
            if (in.longTermMemoryTag()) {
                sb.append("[use_memory:true]");
            }
            if (in.sharedMemoryTag()) {
                sb.append("[shared_memory:true]");
            }
        }
        if (in.systemPrompt() != null && !in.systemPrompt().isBlank()) {
            sb.append(in.systemPrompt().strip()).append('\n');
        } else if (!in.inHouse()) {
            sb.append(MCA_DEFAULT_SYSTEM_PROMPT).append('\n');
        }
        appendBlock(sb, in.editedContext());
        appendBlock(sb, in.mcaDescription());
        appendBlock(sb, in.safetyRule());

        sb.append("\n## What ").append(in.villagerName()).append(" knows right now\n");
        for (AiContextSection section : in.sections()) {
            if (section.isEmpty()) {
                continue;
            }
            sb.append("### ").append(section.title()).append('\n');
            for (String line : section.lines()) {
                sb.append("- ").append(line).append('\n');
            }
        }
        sb.append("### What ").append(in.villagerName()).append(" remembers about ").append(in.playerName()).append('\n');
        if (in.memories().isEmpty()) {
            sb.append("- Nothing in particular yet.\n");
        } else {
            for (AiMemory memory : in.memories()) {
                sb.append("- ").append(memory.text()).append(" (").append(ago(in.today() - memory.day()))
                        .append(", ").append(memory.importance().key()).append(" importance")
                        .append(memory.secret() ? ", told in confidence" : "").append(")\n");
            }
        }
        sb.append("Stay consistent with these memories and refer back to them when it is natural. Never invent ")
                .append("shared history that is not listed here.\n");
        appendVoice(sb, in);

        appendSchema(sb, in);
        if (in.language() != null && !in.language().isBlank()) {
            sb.append("The player's game is in ").append(in.language()).append(". Reply in ").append(in.language())
                    .append(" unless the player clearly writes in another language; then match theirs.\n");
        } else {
            sb.append("Match the language of the player.\n");
        }
        return sb.toString();
    }

    private static void appendSchema(StringBuilder sb, AiPromptInput in) {
        String villager = in.villagerName();
        String player = in.playerName();
        sb.append("\n## Reply format\n");
        sb.append("Reply with exactly one JSON object and nothing else:\n");
        sb.append("{\"message\": \"what ").append(villager).append(" says aloud, in character, 1-3 sentences\",\n");
        if (!in.commands().isEmpty()) {
            sb.append(" \"optionalCommand\": \"one command id from the list below, or empty\",\n");
        }
        sb.append(" \"assessment\": {\"impact\": \"strongly_negative|negative|neutral|positive|strongly_positive\", ")
                .append("\"confidence\": 0.0-1.0},\n");
        sb.append(" \"emotion\": \"neutral|happy|grateful|proud|amused|surprised|sad|hurt|annoyed|angry|afraid\",\n");
        sb.append(" \"memory\": null or {\"text\": \"one short sentence, from ").append(villager)
                .append("'s point of view, about ").append(player).append("\", \"importance\": \"low|medium|high\"},\n");
        sb.append(" \"emotion\" may also be \"smitten\" only where romance is possible,\n");
        sb.append(" \"delivery\": {\"intent\": \"statement|question|reassure|comfort|warn|complain|tease|flirt|confess|")
                .append("refuse|apologize|thank|greet|farewell|exclaim|threaten\", \"tone\": \"a few words on how it is said\", ")
                .append("\"pace\": \"slow|normal|fast\", \"intensity\": 0.0-1.0, \"volume\": \"whisper|normal|raised\"},\n");
        sb.append(" \"effects\": [ ...zero to three of the effects below... ]");
        if (in.bystanders()) {
            sb.append(",\n \"interjection\": null or {\"speaker\": \"exact name of someone close enough to hear\", ")
                    .append("\"message\": \"one short line they say, in their own voice\"}");
        }
        sb.append("}\n");
        sb.append("memory may also carry \"secret\": true when it was told in confidence; secrets are never repeated.\n");
        sb.append("Effects you may use:\n");
        sb.append("- {\"type\": \"disposition\", \"axis\": \"trust|respect|warmth|tension|attraction\", \"direction\": \"up|down\"}")
                .append(" (attraction only where romance is possible)\n");
        sb.append("- {\"type\": \"promise\", \"item\": \"minecraft:item_id or empty\", \"count\": 1-8 (each gift hands over one item), \"days\": 1-7, ")
                .append("\"summary\": \"what ").append(player).append(" promised\"} only when ").append(player)
                .append(" clearly commits to bringing something or coming back\n");
        sb.append("- {\"type\": \"wish\", \"item\": \"minecraft:item_id\", \"days\": 1-10, \"summary\": \"why\"}")
                .append(" when ").append(villager).append(" lets slip something they would love to have\n");
        sb.append("- {\"type\": \"discount\"} when ").append(villager).append(" wants to give a good friend better prices\n");
        sb.append("- {\"type\": \"grudge\"} when ").append(villager).append(" is hurt enough to refuse favours for a while\n");
        sb.append("- {\"type\": \"forgive\"} when ").append(player).append(" sincerely apologises for what caused a grudge\n");
        for (String offer : in.offers()) {
            sb.append("- ").append(offer).append('\n');
        }
        sb.append("Your words and your deeds must agree. If you say you will do something, include its action. ")
                .append("If an action is not listed above, you cannot do it right now: do not pretend you will, say so ")
                .append("honestly or ask for what you would need. The game checks, and a line that promises what cannot ")
                .append("happen is not said.\n");
        sb.append("How to fill it in:\n");
        sb.append("- impact: how ").append(player).append("'s last message changes how ").append(villager)
                .append(" feels about them, judged by what was meant (not by single words), by ").append(villager)
                .append("'s personality, and by their relationship. Small talk, questions and requests are neutral. ")
                .append("Use the strong values only for something genuinely moving or genuinely hurtful.\n");
        sb.append("- confidence: how sure you are of that impact; use a low value when the message is ambiguous or ")
                .append("sarcastic.\n");
        sb.append("- memory: only for something ").append(villager).append(" would still remember days later ")
                .append("(a confession, a promise, a kindness, an insult, a personal fact). Otherwise null.\n");
        sb.append("- effects: usually empty. Add at most one or two when the message clearly affected trust, ")
                .append("respect, warmth or tension between you.\n");
        if (!in.commands().isEmpty()) {
            sb.append("Valid commands (only use one when ").append(player).append(" asks for it):\n");
            for (AiPromptInput.CommandOption command : in.commands()) {
                sb.append("  * ").append(command.id()).append(": ").append(command.description()).append('\n');
            }
        }
    }

    /** How to sound like a person, not an assistant: the part that makes the villager feel real. */
    private static void appendVoice(StringBuilder sb, AiPromptInput in) {
        String villager = in.villagerName();
        String player = in.playerName();
        sb.append("\n## How ").append(villager).append(" talks\n");
        sb.append("- You are ").append(villager).append(", a person with your own day, worries and wants. Speak like ")
                .append("someone in a small village: plain words, short sentences, contractions, the odd trailing thought.\n");
        sb.append("- Usually one to three sentences. Never lists, never headings, never an offer to \"help with anything else\".\n");
        sb.append("- Let your mood, your relationship with ").append(player).append(", and what weighs on you shape ")
                .append("every reply. A stranger gets politeness; a friend gets warmth and teasing; someone who hurt you ")
                .append("gets coldness.\n");
        sb.append("- Have opinions and preferences. Disagree when you disagree. Ask questions back when you are curious. ")
                .append("Bring up your own life, work and neighbours when it fits.\n");
        sb.append("- Remember: refer to promises, past kindnesses and slights naturally, the way people do (\"you still owe me ")
                .append("that wheat\").\n");
        sb.append("- If you are grieving, it shows. If you are in love, it shows. If you are angry, do not pretend otherwise.\n");
        sb.append("- Never mention game mechanics, hearts, JSON, AI, or that you are a character.\n");
    }

    private static void appendBlock(StringBuilder sb, String block) {
        if (block != null && !block.isBlank()) {
            sb.append(block.strip()).append('\n');
        }
    }

    static String ago(long days) {
        if (days <= 0) {
            return "today";
        }
        if (days == 1) {
            return "yesterday";
        }
        return String.format(Locale.ROOT, "%d days ago", days);
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content == null ? "" : content);
        return message;
    }
}
