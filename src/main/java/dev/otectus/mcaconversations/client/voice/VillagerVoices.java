package dev.otectus.mcaconversations.client.voice;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.compat.mca.McaHandles;
import dev.otectus.mcaconversations.voice.VoiceCatalog;
import dev.otectus.mcaconversations.voice.VoiceDirection;
import dev.otectus.mcaconversations.voice.VoiceIntent;
import dev.otectus.mcaconversations.voice.VoiceProvider;
import dev.otectus.mcaconversations.voice.VoiceScript;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Villagers' voices on this client. Replaces MCA's own TTS for a line when an acting-capable engine is
 * configured, so each line is spoken in its language with the emotion, intent and state the server
 * directed; leaves everything to MCA otherwise.
 *
 * <p>Threads: directions and lines arrive on the client thread; synthesis runs on the voice HTTP
 * threads; playback is posted back to the client thread.
 */
public final class VillagerVoices {

    public static final VillagerVoices INSTANCE = new VillagerVoices();

    /** Lines further away than this are not voiced (the listener would barely hear them). */
    static final double LISTEN_RANGE = 32.0;
    static final int CACHE_ENTRIES = 48;
    /** Least time between two "your voice key is wrong" notices. */
    static final long NOTICE_MILLIS = 60_000;

    private final PendingDirections pending = new PendingDirections();
    private final Map<UUID, PcmSoundInstance> playing = new HashMap<>();
    private final Map<String, Pcm> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pcm> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };
    private long lastNotice;

    // --- diagnostics, shown by /mcavoice status ------------------------------------------------------
    /** Villager lines MCA handed to its TTS (proves the hook into MCA is live). */
    int hookCalls;
    /** Lines this mod took over and sent to an engine. */
    int linesVoiced;
    /** Lines left to MCA (engine off or no key). */
    int linesLeftToMca;
    /** Voice directions received from the server. */
    int directionsReceived;
    /** Clips that actually started playing. */
    int clipsPlayed;
    String lastError = "";
    /** What happened to the most recent villager lines, newest first (shown by /mcavoice status). */
    final java.util.Deque<String> decisions = new java.util.ArrayDeque<>();
    long lastLatencyMillis = -1;
    int lastAudioBytes = -1;

    private VillagerVoices() {
    }

    /** A direction from the server. */
    public void onDirection(VoiceDirection direction) {
        directionsReceived++;
        pending.add(direction, System.currentTimeMillis());
    }

    /** Settings, with environment-variable fallbacks for the keys. */
    record Settings(VoiceProvider provider, String openAiKey, String openAiEndpoint, String openAiModel,
                    String geminiKey, String geminiModel, boolean scripted, int maxCharacters, boolean debug) {

        static Settings read() {
            McaConversationsConfig.Client c = McaConversationsConfig.CLIENT;
            return new Settings(c.voiceProvider.get(), key(c.voiceOpenAiApiKey.get(), "OPENAI_API_KEY"),
                    c.voiceOpenAiEndpoint.get(), c.voiceOpenAiModel.get(),
                    key(c.voiceGeminiApiKey.get(), "GEMINI_API_KEY", "GOOGLE_API_KEY"), c.voiceGeminiModel.get(),
                    c.voiceScriptedLines.get(), c.voiceMaxCharacters.get(), c.voiceDebug.get());
        }

        private static String key(String configured, String... env) {
            if (configured != null && !configured.isBlank()) {
                return configured.trim();
            }
            for (String name : env) {
                String value = System.getenv(name);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return "";
        }

        boolean active() {
            return switch (provider) {
                case OPENAI -> !openAiKey.isEmpty();
                case GEMINI -> !geminiKey.isEmpty();
                case MCA -> false;
            };
        }

        SpeechEngine engine() {
            return provider == VoiceProvider.GEMINI ? new GeminiSpeech(geminiKey, geminiModel)
                    : new OpenAiSpeech(openAiEndpoint, openAiKey, openAiModel);
        }
    }

    /**
     * MCA is about to voice a villager line. Returns true when this mod voices it instead (MCA must
     * then stay silent), false to leave it to MCA.
     */
    public boolean intercept(Component message, UUID sender) {
        hookCalls++;
        Settings settings;
        try {
            settings = Settings.read();
        } catch (Throwable t) {
            decide(null, "", "left to MCA: client config not loaded");
            return false;
        }
        String text = ChatFormatting.stripFormatting(message.getString()).strip();
        if (!settings.active()) {
            linesLeftToMca++;
            decide(settings, text, "left to MCA: engine " + settings.provider() + " has no key or is MCA");
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            decide(settings, text, "left to MCA: no world");
            return false;
        }
        Entity villager = findVillager(mc, sender);
        long now = System.currentTimeMillis();
        Optional<VoiceDirection> directed = pending.take(sender, text, now);
        if (directed.isEmpty() && !settings.scripted()) {
            decide(settings, text, "left to MCA: scripted line and voiceScriptedLines is off");
            return false;
        }
        if (villager == null) {
            decide(settings, text, "silent: speaking villager not loaded on this client");
            return true;
        }
        if (text.isEmpty() || McaHandles.silentVoice(villager)) {
            decide(settings, text, "silent: empty line, or a baby/zombified villager");
            return true;
        }
        if (villager.distanceTo(mc.player) > LISTEN_RANGE) {
            decide(settings, text, "silent: villager " + Math.round(villager.distanceTo(mc.player)) + " blocks away");
            return true;
        }
        if (text.length() > settings.maxCharacters()) {
            decide(settings, text, "silent: " + text.length() + " characters, over maxCharacters");
            return true;
        }
        VoiceDirection direction = directed.map(d -> d.anyLine() ? withText(d, text) : d)
                .orElseGet(() -> scripted(villager, text, mc.options.languageCode));
        linesVoiced++;
        decide(settings, text, "sent to " + settings.provider() + (directed.isPresent()
                ? " with the server's direction (" + direction.emotion() + ", " + direction.intent().key() + ")"
                : " as a scripted line"));
        speak(settings, villager, text, direction, null);
        return true;
    }

    private void decide(Settings settings, String text, String what) {
        String shown = text.length() > 40 ? text.substring(0, 40) + "..." : text;
        decisions.addFirst("\"" + shown + "\" -> " + what);
        while (decisions.size() > 5) {
            decisions.removeLast();
        }
        if (settings != null && settings.debug()) {
            McaConversations.LOGGER.info("[voice] line \"{}\" -> {}", shown, what);
        }
    }

    /**
     * {@code /mcavoice test}: synthesises {@code text} with the current settings and plays it from
     * {@code speaker}, reporting each step (or the exact failure) through {@code report}.
     */
    public void test(Entity speaker, String text, String language, java.util.function.Consumer<Component> report) {
        Settings settings;
        try {
            settings = Settings.read();
        } catch (Throwable t) {
            report.accept(Component.literal("Client config not loaded: " + t).withStyle(ChatFormatting.RED));
            return;
        }
        if (!settings.active()) {
            report.accept(Component.literal(settings.provider() == VoiceProvider.MCA
                    ? "Provider is MCA: this mod does not voice lines. Use /mcavoice provider openai|gemini."
                    : "No API key for " + settings.provider() + ". Use /mcavoice key " + settings.provider().name().toLowerCase(Locale.ROOT)
                    + " <key>.").withStyle(ChatFormatting.RED));
            return;
        }
        VoiceDirection direction = McaHandles.isVillager(speaker) ? withText(scripted(speaker, text, language), text)
                : new VoiceDirection(speaker.getUUID(), text, language, "happy", VoiceIntent.GREET, "warm and friendly",
                VoiceDirection.Pace.NORMAL, 0.6f, VoiceDirection.Volume.NORMAL, "female", "adult", "", "", false, false, false);
        report.accept(Component.literal("Synthesising with " + settings.provider() + " ("
                + VoiceCatalog.voiceFor(settings.provider(), speaker.getUUID(), direction.gender(), direction.age()) + ")...")
                .withStyle(ChatFormatting.GRAY));
        speak(settings, speaker, text, direction, report);
    }

    private void speak(Settings settings, Entity villager, String text, VoiceDirection direction,
                       java.util.function.Consumer<Component> report) {
        String voice = VoiceCatalog.voiceFor(settings.provider(), villager.getUUID(), direction.gender(), direction.age());
        String key = settings.provider() + "|" + voice + "|" + VoiceScript.instructions(direction) + "|" + text;
        Pcm cached = cache.get(key);
        long started = System.currentTimeMillis();
        CompletableFuture<Pcm> audio;
        try {
            audio = cached != null ? CompletableFuture.completedFuture(cached) : settings.engine().speak(text, voice, direction);
        } catch (Throwable t) {
            audio = CompletableFuture.failedFuture(t); // e.g. an endpoint that is not a valid URL
        }
        audio.whenComplete((pcm, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null) {
                failed(settings, error);
                if (report != null) {
                    report.accept(Component.literal("Failed: " + lastError).withStyle(ChatFormatting.RED));
                }
                return;
            }
            lastLatencyMillis = System.currentTimeMillis() - started;
            lastAudioBytes = pcm.data().length;
            if (pcm.data().length < 2) {
                lastError = "the engine returned no audio";
                if (report != null) {
                    report.accept(Component.literal("Failed: " + lastError).withStyle(ChatFormatting.RED));
                }
                return;
            }
            cache.put(key, pcm);
            if (report != null) {
                report.accept(Component.literal(String.format(Locale.ROOT, "OK: %d ms, %.1f s of audio at %d Hz. Playing now - "
                                + "if you hear nothing, check the Voice/Speech volume slider.", lastLatencyMillis,
                        pcm.data().length / 2.0 / pcm.sampleRate(), pcm.sampleRate())).withStyle(ChatFormatting.GREEN));
            }
            if (settings.debug()) {
                McaConversations.LOGGER.info("[voice] {} voice={} {}ms lang={} emotion={} intent={} brief=\"{}\"",
                        settings.provider(), voice, System.currentTimeMillis() - started, direction.language(),
                        direction.emotion(), direction.intent().key(), VoiceScript.instructions(direction));
            }
            play(villager, pcm);
        }));
    }

    private void play(Entity villager, Pcm pcm) {
        if (villager.isRemoved()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        PcmSoundInstance previous = playing.remove(villager.getUUID());
        if (previous != null) {
            mc.getSoundManager().stop(previous);
        }
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(McaConversations.MOD_ID,
                "voice/" + Integer.toHexString(System.identityHashCode(pcm)));
        PcmSoundInstance instance = new PcmSoundInstance(villager, pcm, id, villager.getRandom().nextLong());
        playing.put(villager.getUUID(), instance);
        mc.getSoundManager().play(instance);
        clipsPlayed++;
    }

    /** True while a model lookup after a 404 is running, so a burst of failures triggers one lookup. */
    private boolean recoveringModel;

    private void failed(Settings settings, Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        lastError = cause.toString();
        if (cause instanceof Http.Failure failure && failure.status == 404 && settings.provider() == VoiceProvider.GEMINI
                && !recoveringModel) {
            recoverGeminiModel(settings);
        }
        McaConversations.LOGGER.warn("[voice] {} could not voice a line: {}", settings.provider(), cause.toString());
        long now = System.currentTimeMillis();
        if (cause instanceof Http.Failure failure && now - lastNotice > NOTICE_MILLIS && Minecraft.getInstance().player != null) {
            if (failure.status == 401 || failure.status == 403) {
                lastNotice = now;
                Minecraft.getInstance().player.displayClientMessage(
                        Component.translatable("mcaconversations.voice.key_rejected", settings.provider().name())
                                .withStyle(ChatFormatting.GRAY), false);
            } else if (failure.status == 429) {
                lastNotice = now;
                Minecraft.getInstance().player.displayClientMessage(Component.literal("Voice: " + settings.provider()
                        + " usage limit reached (HTTP 429). Lines stay silent until it resets; /mcavoice scripted off "
                        + "saves quota for AI conversations.").withStyle(ChatFormatting.YELLOW), false);
            }
        }
    }

    /**
     * Gemini said the configured model does not exist (Google renames its preview TTS models). Asks
     * which TTS models this key can use, switches to the best one, saves it and tells the player.
     */
    private void recoverGeminiModel(Settings settings) {
        recoveringModel = true;
        ModelCatalog.gemini(settings.geminiKey()).whenComplete((models, error) -> Minecraft.getInstance().execute(() -> {
            recoveringModel = false;
            Minecraft mc = Minecraft.getInstance();
            if (error != null || models == null || models.isEmpty()) {
                McaConversations.LOGGER.warn("[voice] Gemini model '{}' not found and no TTS model is listed for this key",
                        settings.geminiModel());
                return;
            }
            String chosen = models.get(0);
            McaConversationsConfig.CLIENT.voiceGeminiModel.set(chosen);
            McaConversationsConfig.CLIENT.voiceGeminiModel.save();
            McaConversations.LOGGER.info("[voice] Gemini model '{}' not found; switched to '{}'", settings.geminiModel(), chosen);
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("Voice: Gemini model '" + settings.geminiModel()
                        + "' does not exist; switched to '" + chosen + "'. Try again with /mcavoice test.")
                        .withStyle(ChatFormatting.YELLOW), false);
            }
        }));
    }

    /** A line no direction was sent for (a scripted line): spoken plainly, coloured by the villager's mood. */
    static VoiceDirection scripted(Entity villager, String text, String language) {
        String mood = McaCompat.getMoodName(villager).map(m -> m.replace('_', ' ').toLowerCase(Locale.ROOT)).orElse("");
        return new VoiceDirection(villager.getUUID(), text, language, "neutral", VoiceIntent.STATEMENT, "", VoiceDirection.Pace.NORMAL,
                0.4f, VoiceDirection.Volume.NORMAL, McaHandles.gender(villager),
                McaCompat.ageGroup(villager).name().toLowerCase(Locale.ROOT),
                McaCompat.getPersonality(villager).map(p -> p.replace('_', ' ').toLowerCase(Locale.ROOT)).orElse(""),
                mood, false, false, false);
    }

    private static VoiceDirection withText(VoiceDirection d, String text) {
        return new VoiceDirection(d.villager(), text, d.language(), d.emotion(), d.intent(), d.tone(), d.pace(),
                d.intensity(), d.volume(), d.gender(), d.age(), d.personality(), d.mood(), d.grieving(), d.romantic(), d.cold());
    }

    private static Entity findVillager(Minecraft mc, UUID id) {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.getUUID().equals(id)) {
                return entity;
            }
        }
        return null;
    }

    /** Leaving a world: forget pending directions and stop every voice. */
    public void reset() {
        pending.clear();
        Minecraft mc = Minecraft.getInstance();
        playing.values().forEach(mc.getSoundManager()::stop);
        playing.clear();
    }
}
