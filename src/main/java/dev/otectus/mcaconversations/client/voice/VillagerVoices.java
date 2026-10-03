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

    private VillagerVoices() {
    }

    /** A direction from the server. */
    public void onDirection(VoiceDirection direction) {
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
        Settings settings;
        try {
            settings = Settings.read();
        } catch (Throwable t) {
            return false; // config not loaded yet
        }
        if (!settings.active()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }
        Entity villager = findVillager(mc, sender);
        String text = ChatFormatting.stripFormatting(message.getString()).strip();
        long now = System.currentTimeMillis();
        Optional<VoiceDirection> directed = pending.take(sender, text, now);
        if (directed.isEmpty() && !settings.scripted()) {
            return false;
        }
        if (villager == null || text.isEmpty() || McaHandles.silentVoice(villager)) {
            return true; // nothing to say aloud; MCA would not have voiced it either
        }
        if (villager.distanceTo(mc.player) > LISTEN_RANGE || text.length() > settings.maxCharacters()) {
            return true;
        }
        VoiceDirection direction = directed.map(d -> d.anyLine() ? withText(d, text) : d)
                .orElseGet(() -> scripted(villager, text, mc.options.languageCode));
        speak(settings, villager, text, direction);
        return true;
    }

    private void speak(Settings settings, Entity villager, String text, VoiceDirection direction) {
        String voice = VoiceCatalog.voiceFor(settings.provider(), villager.getUUID(), direction.gender(), direction.age());
        String key = settings.provider() + "|" + voice + "|" + VoiceScript.instructions(direction) + "|" + text;
        Pcm cached = cache.get(key);
        long started = System.currentTimeMillis();
        CompletableFuture<Pcm> audio = cached != null ? CompletableFuture.completedFuture(cached)
                : settings.engine().speak(text, voice, direction);
        audio.whenComplete((pcm, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null) {
                failed(settings, error);
                return;
            }
            cache.put(key, pcm);
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
    }

    private void failed(Settings settings, Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        McaConversations.LOGGER.warn("[voice] {} could not voice a line: {}", settings.provider(), cause.toString());
        long now = System.currentTimeMillis();
        if (cause instanceof Http.Failure failure && (failure.status == 401 || failure.status == 403)
                && now - lastNotice > NOTICE_MILLIS && Minecraft.getInstance().player != null) {
            lastNotice = now;
            Minecraft.getInstance().player.displayClientMessage(
                    Component.translatable("mcaconversations.voice.key_rejected", settings.provider().name())
                            .withStyle(ChatFormatting.GRAY), false);
        }
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
