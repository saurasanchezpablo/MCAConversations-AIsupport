package dev.otectus.mcaconversations.compat.mca;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * MCA's villager chat AI, as the AI-conversation integration consumes it: configuration, villager
 * description, user-edited context, and the command allow-list. Every member is bound by name through
 * {@link McaBinding}, so nothing here names an MCA type; every method is safe to call when MCA is
 * absent or a member did not bind, and answers the documented empty value instead of throwing.
 *
 * <p>Server thread only, like everything MCA's own {@code OpenAIChatAI} does with these: the prompt
 * modules read entity state, and the command callbacks mutate the villager's brain.
 */
public final class McaChatAi {

    private static final McaBinding.Resolution R = McaHandles.resolution();

    private static final MethodHandle H_CONFIG = R.handle(McaBinding.CONFIG_GET_INSTANCE);
    private static final MethodHandle H_ENABLED = R.handle(McaBinding.CONFIG_CHAT_AI_ENABLED);
    private static final MethodHandle H_ENDPOINT = R.handle(McaBinding.CONFIG_CHAT_AI_ENDPOINT);
    private static final MethodHandle H_MODEL = R.handle(McaBinding.CONFIG_CHAT_AI_MODEL);
    private static final MethodHandle H_TOKEN = R.handle(McaBinding.CONFIG_CHAT_AI_TOKEN);
    private static final MethodHandle H_SYSTEM_PROMPT = R.handle(McaBinding.CONFIG_CHAT_AI_SYSTEM_PROMPT);
    private static final MethodHandle H_USE_TOOLS = R.handle(McaBinding.CONFIG_CHAT_AI_USE_TOOLS);
    private static final MethodHandle H_SESSION_INFO = R.handle(McaBinding.CONFIG_CHAT_AI_SESSION_INFO);
    private static final MethodHandle H_LONG_TERM = R.handle(McaBinding.CONFIG_CHAT_AI_LONG_TERM_MEMORY);
    private static final MethodHandle H_SHARED = R.handle(McaBinding.CONFIG_CHAT_AI_SHARED_MEMORY);
    private static final MethodHandle H_LANGUAGE = R.handle(McaBinding.MCA_LANGUAGE);

    /** MCA's six prompt modules, in the order {@code OpenAIChatAI} applies them. */
    private static final List<MethodHandle> MODULES = List.of(
            R.handle(McaBinding.CHAT_AI_PERSONALITY_MODULE),
            R.handle(McaBinding.CHAT_AI_TRAITS_MODULE),
            R.handle(McaBinding.CHAT_AI_RELATION_MODULE),
            R.handle(McaBinding.CHAT_AI_VILLAGE_MODULE),
            R.handle(McaBinding.CHAT_AI_ENVIRONMENT_MODULE),
            R.handle(McaBinding.CHAT_AI_PLAYER_MODULE));
    private static final MethodHandle H_APPEND_PROMPTS = R.handle(McaBinding.CHAT_AI_APPEND_PROMPTS);
    private static final boolean HAS_APPEND_PROMPTS = R.has(McaBinding.CHAT_AI_APPEND_PROMPTS);
    private static final MethodHandle H_FIND_NEAREST_VILLAGE = R.handle(McaBinding.VILLAGE_FIND_NEAREST);

    private static final MethodHandle H_TRIGGERS = R.handle(McaBinding.TRIGGER_COMMANDS);
    private static final MethodHandle H_FIND_COMMAND = R.handle(McaBinding.TRIGGER_FIND_COMMAND);
    private static final MethodHandle H_TRIGGER_COMMAND = R.handle(McaBinding.TRIGGER_COMMAND);
    private static final MethodHandle H_TRIGGER_DESCRIPTION = R.handle(McaBinding.TRIGGER_DESCRIPTION);
    private static final MethodHandle H_TRIGGER_IS_ACTIVE = R.handle(McaBinding.TRIGGER_IS_ACTIVE);
    private static final MethodHandle H_TRIGGER_CALL = R.handle(McaBinding.TRIGGER_CALL);

    /** MCA's hosted endpoint, which takes the player name as its token and keys its own memory. */
    private static final String IN_HOUSE_HOST = "conczin.net";

    private McaChatAi() {
    }

    /**
     * The chat-AI settings a server admin configured through {@code /mca chatAI} or MCA's config.
     *
     * @param language MCA's fallback language hint, or null (it is client-side state; usually null on a server)
     */
    public record Settings(boolean enabled, String endpoint, String model, String token, String systemPrompt,
                           boolean useTools, boolean includeSessionInfo, boolean longTermMemory,
                           boolean sharedLongTermMemory, String language) {

        /** True for MCA's hosted service, which MCA itself treats differently (token, prompt, session tags). */
        public boolean inHouse() {
            return endpoint != null && endpoint.contains(IN_HOUSE_HOST);
        }

        /** The bearer token exactly as MCA computes it: the player's name for the hosted service. */
        public String tokenFor(String playerName) {
            return token == null || token.isEmpty() || inHouse() ? playerName : token;
        }
    }

    /** One entry of MCA's command allow-list that is currently valid for this villager and player. */
    public record Command(String id, String description) {
    }

    /** True when MCA's chat-AI binding is usable at all (config and description modules bound). */
    public static boolean available() {
        return McaHandles.available() && R.has(McaBinding.CONFIG_CHAT_AI_ENDPOINT)
                && R.has(McaBinding.CHAT_AI_PERSONALITY_MODULE);
    }

    /** MCA's current chat-AI settings, or empty when MCA's config cannot be read. */
    public static Optional<Settings> settings() {
        try {
            Object config = H_CONFIG.invoke();
            if (config == null) {
                return Optional.empty();
            }
            String endpoint = string(H_ENDPOINT, config);
            if (endpoint == null || endpoint.isBlank()) {
                return Optional.empty();
            }
            Object language = R.has(McaBinding.MCA_LANGUAGE) ? H_LANGUAGE.invoke() : null;
            return Optional.of(new Settings(bool(H_ENABLED, config), endpoint, orEmpty(string(H_MODEL, config)),
                    orEmpty(string(H_TOKEN, config)), orEmpty(string(H_SYSTEM_PROMPT, config)),
                    bool(H_USE_TOOLS, config), bool(H_SESSION_INFO, config), bool(H_LONG_TERM, config),
                    bool(H_SHARED, config), language instanceof String s && !s.isBlank() ? s : null));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /**
     * MCA's own description of the villager, exactly as its prompt modules phrase it, with
     * {@code $player}/{@code $villager} substituted. Empty when the modules did not bind.
     */
    public static String describeVillager(Entity villager, ServerPlayer player, String playerName, String villagerName) {
        if (!McaHandles.isVillager(villager) || player == null) {
            return "";
        }
        List<String> input = new LinkedList<>();
        for (MethodHandle module : MODULES) {
            try {
                module.invoke(input, villager, player);
            } catch (Throwable ignored) {
                // One module failing (an advancement lookup on a modded server) must not lose the rest.
            }
        }
        StringBuilder out = new StringBuilder();
        for (String line : input) {
            if (line != null) {
                // replace, not MCA 7.6's replaceAll: a name containing '$' must not become a group reference.
                out.append(line.replace("$player", playerName).replace("$villager", villagerName));
            }
        }
        return out.toString().strip();
    }

    /**
     * The villager background, player context and village context strings that operators edit with
     * {@code /mca chatAI context} (MCA 7.7.0 and later). Empty on older MCA builds.
     */
    public static String editedContext(Entity villager, ServerPlayer player) {
        if (!HAS_APPEND_PROMPTS || !McaHandles.isVillager(villager) || player == null) {
            return "";
        }
        try {
            Object village = H_FIND_NEAREST_VILLAGE.invoke(villager);
            StringBuilder out = new StringBuilder();
            H_APPEND_PROMPTS.invoke(out, player, villager,
                    village instanceof Optional<?> opt ? opt.orElse(null) : null);
            return out.toString().strip();
        } catch (Throwable t) {
            return "";
        }
    }

    /** MCA's command allow-list filtered by each command's own {@code isActive} test, as MCA filters it. */
    @SuppressWarnings("unchecked")
    public static List<Command> activeCommands(Entity villager, ServerPlayer player) {
        List<Command> out = new ArrayList<>();
        if (!McaHandles.isVillager(villager) || player == null) {
            return out;
        }
        try {
            if (!(H_TRIGGERS.invoke() instanceof List<?> triggers)) {
                return out;
            }
            for (Object trigger : triggers) {
                Object id = H_TRIGGER_COMMAND.invoke(trigger);
                Object description = H_TRIGGER_DESCRIPTION.invoke(trigger);
                Object isActive = H_TRIGGER_IS_ACTIVE.invoke(trigger);
                if (!(id instanceof String commandId) || commandId.isBlank()) {
                    continue;
                }
                if (isActive instanceof BiPredicate<?, ?> predicate
                        && !((BiPredicate<Object, Object>) predicate).test(player, villager)) {
                    continue;
                }
                out.add(new Command(commandId, description instanceof String d ? d : ""));
            }
        } catch (Throwable ignored) {
            // A command table MCA reshaped degrades to "no commands", never to a failed turn.
        }
        return out;
    }

    /**
     * Runs one of MCA's allow-listed commands through MCA's own lookup, which re-checks
     * {@code isActive}. Returns whether a command ran. Never runs anything that is not on MCA's list.
     */
    @SuppressWarnings("unchecked")
    public static boolean runCommand(String commandId, Entity villager, ServerPlayer player) {
        if (commandId == null || commandId.isBlank() || !McaHandles.isVillager(villager) || player == null) {
            return false;
        }
        try {
            Object found = H_FIND_COMMAND.invoke(commandId, player, villager);
            Object info = found instanceof Optional<?> opt ? opt.orElse(null) : null;
            if (info == null) {
                return false;
            }
            if (H_TRIGGER_CALL.invoke(info) instanceof BiConsumer<?, ?> call) {
                ((BiConsumer<Object, Object>) call).accept(player, villager);
                return true;
            }
        } catch (Throwable ignored) {
            // A command that throws inside MCA must not take the rest of the outcome with it.
        }
        return false;
    }

    private static String string(MethodHandle getter, Object config) throws Throwable {
        return getter.invoke(config) instanceof String s ? s : null;
    }

    private static boolean bool(MethodHandle getter, Object config) throws Throwable {
        return getter.invoke(config) instanceof Boolean b && b;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
