package dev.otectus.mcaconversations.client.voice;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.voice.VoiceProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Comparator;
import java.util.Locale;

/**
 * {@code /mcavoice}: check and configure villager voices from inside the game. A client command:
 * it runs on this client only, so API keys typed here are never sent to the server; they are saved to
 * {@code mcaconversations-client.toml} like everything else under {@code [voice]}.
 *
 * <pre>
 * /mcavoice status                       what is configured, and what has happened so far
 * /mcavoice test [text]                  speak a test line from the nearest villager (or yourself)
 * /mcavoice provider mca|openai|gemini   choose the engine
 * /mcavoice key openai|gemini &lt;key&gt;      store an API key (shown masked afterwards)
 * /mcavoice model openai|gemini &lt;model&gt;  choose the model
 * /mcavoice endpoint &lt;url&gt;               OpenAI-compatible speech endpoint
 * /mcavoice scripted on|off              also voice scripted lines, or only AI conversations
 * /mcavoice debug on|off                 log each voiced line
 * </pre>
 */
@EventBusSubscriber(modid = McaConversations.MOD_ID, value = Dist.CLIENT)
public final class VoiceCommands {

    private VoiceCommands() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcavoice")
                .executes(ctx -> status())
                .then(Commands.literal("status").executes(ctx -> status()))
                .then(Commands.literal("test")
                        .executes(ctx -> test(null))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> test(StringArgumentType.getString(ctx, "text")))))
                .then(Commands.literal("provider")
                        .then(Commands.literal("mca").executes(ctx -> provider(VoiceProvider.MCA)))
                        .then(Commands.literal("openai").executes(ctx -> provider(VoiceProvider.OPENAI)))
                        .then(Commands.literal("gemini").executes(ctx -> provider(VoiceProvider.GEMINI))))
                .then(Commands.literal("key")
                        .then(Commands.literal("openai").then(Commands.argument("key", StringArgumentType.greedyString())
                                .executes(ctx -> set(McaConversationsConfig.CLIENT.voiceOpenAiApiKey, arg(ctx, "key"), "OpenAI key", true))))
                        .then(Commands.literal("gemini").then(Commands.argument("key", StringArgumentType.greedyString())
                                .executes(ctx -> set(McaConversationsConfig.CLIENT.voiceGeminiApiKey, arg(ctx, "key"), "Gemini key", true)))))
                .then(Commands.literal("model")
                        .then(Commands.literal("openai").then(Commands.argument("model", StringArgumentType.greedyString())
                                .executes(ctx -> set(McaConversationsConfig.CLIENT.voiceOpenAiModel, arg(ctx, "model"), "OpenAI model", false))))
                        .then(Commands.literal("gemini").then(Commands.argument("model", StringArgumentType.greedyString())
                                .executes(ctx -> set(McaConversationsConfig.CLIENT.voiceGeminiModel, arg(ctx, "model"), "Gemini model", false)))))
                .then(Commands.literal("endpoint").then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> set(McaConversationsConfig.CLIENT.voiceOpenAiEndpoint, arg(ctx, "url"), "OpenAI endpoint", false))))
                .then(Commands.literal("scripted")
                        .then(Commands.literal("on").executes(ctx -> toggle(McaConversationsConfig.CLIENT.voiceScriptedLines, true, "Voice scripted lines")))
                        .then(Commands.literal("off").executes(ctx -> toggle(McaConversationsConfig.CLIENT.voiceScriptedLines, false, "Voice scripted lines"))))
                .then(Commands.literal("debug")
                        .then(Commands.literal("on").executes(ctx -> toggle(McaConversationsConfig.CLIENT.voiceDebug, true, "Voice debug log")))
                        .then(Commands.literal("off").executes(ctx -> toggle(McaConversationsConfig.CLIENT.voiceDebug, false, "Voice debug log")))));
    }

    private static String arg(CommandContext<CommandSourceStack> ctx, String name) {
        return StringArgumentType.getString(ctx, name).trim();
    }

    private static int status() {
        VillagerVoices v = VillagerVoices.INSTANCE;
        VillagerVoices.Settings s;
        try {
            s = VillagerVoices.Settings.read();
        } catch (Throwable t) {
            say(Component.literal("Client config is not loaded yet.").withStyle(ChatFormatting.RED));
            return 0;
        }
        Minecraft mc = Minecraft.getInstance();
        say(Component.literal("Villager voice").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        line("Engine", s.provider() + (s.active() ? "  (active)" : s.provider() == VoiceProvider.MCA
                ? "  (MCA's own TTS, no acting)" : "  (INACTIVE: no API key)"), s.active() || s.provider() == VoiceProvider.MCA);
        line("OpenAI key", mask(s.openAiKey()) + "  model " + s.openAiModel(), true);
        line("Gemini key", mask(s.geminiKey()) + "  model " + s.geminiModel(), true);
        line("OpenAI endpoint", s.openAiEndpoint(), true);
        line("Scripted lines", s.scripted() ? "voiced" : "AI conversations only", true);
        line("Game language", mc.options.languageCode, true);
        float volume = mc.options.getSoundSourceVolume(SoundSource.VOICE) * mc.options.getSoundSourceVolume(SoundSource.MASTER);
        line("Voice volume", Math.round(volume * 100) + "%", volume > 0);
        line("MCA hook", v.hookCalls > 0 ? v.hookCalls + " villager lines seen" : "no villager line seen yet (talk to one)",
                v.hookCalls > 0);
        line("Directions from server", String.valueOf(v.directionsReceived), true);
        line("Voiced / left to MCA / played", v.linesVoiced + " / " + v.linesLeftToMca + " / " + v.clipsPlayed, true);
        if (v.lastLatencyMillis >= 0) {
            line("Last synthesis", v.lastLatencyMillis + " ms, " + v.lastAudioBytes / 1024 + " KB", true);
        }
        if (!v.lastError.isEmpty()) {
            line("Last error", v.lastError, false);
        }
        return 1;
    }

    private static int test(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return 0;
        }
        String language = mc.options.languageCode;
        String line = text != null && !text.isBlank() ? text
                : language.startsWith("es") ? "¡Hola! Qué alegría verte por aquí. ¿Cómo te va el día?"
                : "Hello there! It's good to see you. How's your day going?";
        Entity speaker = mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(16), McaCompat::isMcaVillager)
                .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player))).orElse(mc.player);
        say(Component.literal("Testing voice from " + (speaker == mc.player ? "you (no villager nearby)"
                : McaCompat.getVillagerName(speaker).orElse("a villager")) + ".").withStyle(ChatFormatting.GRAY));
        VillagerVoices.INSTANCE.test(speaker, line, language, VoiceCommands::say);
        return 1;
    }

    private static int provider(VoiceProvider provider) {
        McaConversationsConfig.CLIENT.voiceProvider.set(provider);
        McaConversationsConfig.CLIENT.voiceProvider.save();
        say(Component.literal("Voice engine set to " + provider + ".").withStyle(ChatFormatting.GREEN));
        if (provider != VoiceProvider.MCA) {
            say(Component.literal("Next: /mcavoice key " + provider.name().toLowerCase(Locale.ROOT)
                    + " <key>, then /mcavoice test").withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    private static int set(ModConfigSpec.ConfigValue<String> value, String text, String what, boolean secret) {
        value.set(text);
        value.save();
        say(Component.literal(what + " saved: " + (secret ? mask(text) : text)).withStyle(ChatFormatting.GREEN));
        if (secret) {
            say(Component.literal("It stays on this computer (mcaconversations-client.toml). Your chat history still holds "
                    + "the command you typed; it is never sent to the server.").withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    private static int toggle(ModConfigSpec.BooleanValue value, boolean on, String what) {
        value.set(on);
        value.save();
        say(Component.literal(what + ": " + (on ? "on" : "off")).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    static String mask(String key) {
        if (key == null || key.isEmpty()) {
            return "(not set)";
        }
        return key.length() <= 8 ? "****" : key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    private static void line(String label, String value, boolean ok) {
        say(Component.literal(" " + label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ok ? ChatFormatting.WHITE : ChatFormatting.RED)));
    }

    private static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(message, false);
        }
    }
}
