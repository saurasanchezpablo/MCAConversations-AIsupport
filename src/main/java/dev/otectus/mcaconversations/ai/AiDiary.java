package dev.otectus.mcaconversations.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.otectus.mcaconversations.gossip.GossipEvent;
import dev.otectus.mcaconversations.gossip.GossipSavedData;
import dev.otectus.mcaconversations.progress.AffectionMath;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player's diary: one place to read where they stand. It covers:
 * <ul>
 *   <li>who they know and how well, and who holds a grudge;</li>
 *   <li>what they promised and what someone is hoping for;</li>
 *   <li>dates, village events coming up, and who leads or is standing for election;</li>
 *   <li>peace offers they carry;</li>
 *   <li>what the village says about them, and who remembers them from childhood or trusted them with a secret.</li>
 * </ul>
 * Shown in chat ({@code /diary}) or given as a written book ({@code /diary book}).
 */
public final class AiDiary {

    static final int MAX_PEOPLE = 12;

    /** One section: a heading and its lines, each translated on the reader's side. */
    record Section(String key, List<Component> lines) {
    }

    private static final String KEY = "mcaconversations.diary.";
    /** The book title, by language: a written book's title is plain text, so it is looked up here. */
    private static final Map<String, String> BOOK_TITLES = new ConcurrentHashMap<>();
    /** A written book's title can be no longer than this. */
    private static final int MAX_TITLE = 32;

    private AiDiary() {
    }

    private static MutableComponent tr(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }

    static List<Section> sections(MinecraftServer server, ServerPlayer player) {
        List<Section> out = new ArrayList<>();
        UUID me = player.getUUID();
        long gameNow = server.overworld().getGameTime();
        long day = AffectionMath.dayOf(gameNow);
        long now = server.overworld().getDayTime();
        Map<UUID, AiPairMemory> pairs = AiMemorySavedData.get(server).pairsOf(me);

        List<Component> people = new ArrayList<>();
        pairs.entrySet().stream().filter(e -> !e.getValue().villagerName().isEmpty())
                .sorted(Comparator.comparingInt((Map.Entry<UUID, AiPairMemory> e) -> -e.getValue().lastHearts()))
                .limit(MAX_PEOPLE).forEach(e -> {
                    AiPairMemory p = e.getValue();
                    long ago = p.lastTalkDay() < 0 ? -1 : day - p.lastTalkDay();
                    MutableComponent line = tr("person", p.villagerName(), p.lastHearts());
                    if (ago >= 0) {
                        line.append(ago == 0 ? tr("person.today") : tr("person.ago", ago));
                    }
                    if (p.grudge(gameNow)) {
                        line.append(tr("person.grudge"));
                    }
                    people.add(line);
                });
        out.add(new Section("people", people));

        List<Component> promises = new ArrayList<>();
        pairs.values().forEach(p -> {
            p.promises().stream().filter(AiPromise::pending).forEach(pr -> promises.add(tr("promise", p.villagerName(),
                    promise(pr)).append(pr.dueDay() - day < 0 ? tr("promise.overdue") : pr.dueDay() == day
                    ? tr("promise.today") : tr("promise.in", pr.dueDay() - day))));
            p.wish(day).ifPresent(w -> promises.add(tr("wish", p.villagerName(), item(w.item()))));
        });
        out.add(new Section("promises", promises));

        List<Component> dates = new ArrayList<>();
        for (AiLivesSavedData.Date d : AiLivesSavedData.get(server).dates()) {
            if (d.player.equals(me) && d.state != AiLivesSavedData.Date.State.DONE) {
                long in = d.start - now;
                Component when = d.state == AiLivesSavedData.Date.State.ON ? tr("date.now")
                        : in <= 0 ? tr("date.waiting") : in < 2_000 ? tr("date.soon")
                        : Component.translatable(Math.floorDiv(d.start, 24_000L) == Math.floorDiv(now, 24_000L)
                        ? "mcaconversations.ai.date.when.evening" : "mcaconversations.ai.date.when.tomorrow");
                dates.add(d.place.isEmpty() ? tr("date", d.villagerName, when)
                        : tr("date.at", d.villagerName, AiVillageEvents.placeName(d.place), when));
            }
        }
        out.add(new Section("dates", dates));

        List<Component> village = new ArrayList<>();
        AiVillageLifeSavedData life = AiVillageLifeSavedData.get(server);
        for (AiVillageEvent e : life.events()) {
            if (!e.ended && e.type.gathering()) {
                MutableComponent line = tr("event", AiVillageEvents.title(e), when(e, now));
                if (e.attended.contains(me)) {
                    line.append(tr("event.went"));
                }
                village.add(line);
            }
        }
        long today = Math.floorDiv(now, AiVillageEventType.DAY);
        for (AiVillageLifeSavedData.Census c : life.censuses().values()) {
            if (c.leader != null) {
                village.add(tr("leader", c.leaderName, platform(c.leaderPlatform)));
            }
            if (c.electionPending()) {
                UUID backed = c.backers.get(me);
                MutableComponent line = tr("election", Math.max(0, c.nextElectionDay - today), c.candidateNames.get(0),
                        c.candidateNames.get(1));
                if (backed != null && c.candidates.contains(backed)) {
                    line.append(tr("election.backing", c.candidateNames.get(c.candidates.indexOf(backed))));
                }
                village.add(line);
            }
            int kills = c.defenders.getOrDefault(me, 0);
            if (kills > 0) {
                village.add(tr("kills", kills));
            }
        }
        out.add(new Section("village", village));

        List<Component> ties = new ArrayList<>();
        for (AiVillageEvent e : life.events()) {
            if (e.type == AiVillageEventType.QUARREL && e.started) {
                ties.add(tr("quarrel", e.name(0), e.name(1), e.cause.isEmpty() ? tr("quarrel.something")
                        : AiVillageEvents.cause(e.cause)));
            }
        }
        pairs.forEach((id, p) -> {
            if (!p.secrets().isEmpty()) {
                ties.add(tr("secret", p.villagerName()));
            }
            AiLivesSavedData.get(server).childhood(id, me).ifPresent(c -> {
                String tone = AiChildhood.tone(c.score);
                if (!tone.isEmpty()) {
                    ties.add(tr("childhood", p.villagerName(), tr("tone." + tone.replace(' ', '_'))));
                }
            });
        });
        out.add(new Section("ties", ties));

        List<Component> rumours = new ArrayList<>();
        GossipSavedData.get(server).log().events().stream()
                .filter(e -> e.type().aboutListener() && me.equals(e.aUuid()) && gameNow - e.created() <= 24_000L * 7)
                .sorted(Comparator.comparingLong(GossipEvent::created).reversed()).limit(5)
                .forEach(e -> rumours.add(tr(e.type().name().endsWith("KINDNESS") ? "rumour.kind" : "rumour.cruel",
                        e.bName())));
        out.add(new Section("rumours", rumours));
        return out;
    }

    /** What was promised: the villager's own words when they gave them, else what the game knows. */
    private static Component promise(AiPromise promise) {
        if (!promise.summary().isEmpty()) {
            return Component.literal(promise.summary());
        }
        return promise.isVisit() ? tr("promise.visit") : tr("promise.bring", promise.count(), item(promise.item()));
    }

    /** An item by its own (translated) name; a tag, which has none, in words. */
    static Component item(String ref) {
        if (ref != null && !ref.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(ref);
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                return BuiltInRegistries.ITEM.get(id).getDescription();
            }
        }
        return Component.literal(AiContextFormat.words(ref == null ? "" : ref.replace("#", "")));
    }

    private static Component platform(String key) {
        return Component.translatableWithFallback(KEY + "platform." + key, AiPolitics.platformWords(key));
    }

    /** As {@link AiVillageEvent#when(long)}, translated. */
    private static Component when(AiVillageEvent event, long now) {
        long today = Math.floorDiv(now, AiVillageEventType.DAY);
        if (event.active(now)) {
            return tr("when.now");
        }
        if (event.upcoming(now)) {
            long start = Math.floorDiv(event.start, AiVillageEventType.DAY);
            long hour = Math.floorMod(event.start, AiVillageEventType.DAY);
            String part = hour < 6_000 ? "morning" : hour < 10_000 ? "afternoon" : "evening";
            if (start == today) {
                return tr("when.this_" + part);
            }
            return start == today + 1 ? tr("when.tomorrow_" + part) : tr("when.in_days", start - today);
        }
        long ago = today - Math.floorDiv(event.end, AiVillageEventType.DAY);
        return ago <= 0 ? tr("when.earlier_today") : ago == 1 ? tr("when.yesterday") : tr("when.days_ago", ago);
    }

    /**
     * The book's title in the player's language. A written book stores its title as plain text, so it
     * cannot be translated on the client like the pages are; the title is read from this mod's own
     * language file for the language the player's client reports (English when there is none).
     */
    static String bookTitle(String language) {
        String code = language == null ? "en_us" : language.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return BOOK_TITLES.computeIfAbsent(code.isEmpty() ? "en_us" : code, c -> {
            String title = langValue(c, KEY + "book");
            if (title == null && !c.equals("en_us")) {
                title = langValue("en_us", KEY + "book");
            }
            title = title == null ? "" : AiText.clean(title, MAX_TITLE);
            return title.isBlank() ? "Diary" : title;
        });
    }

    private static String langValue(String code, String key) {
        if (!code.matches("[a-z0-9_]+")) {
            return null;
        }
        try (InputStream in = AiDiary.class.getResourceAsStream("/assets/mcaconversations/lang/" + code + ".json")) {
            if (in == null) {
                return null;
            }
            JsonElement value = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().get(key);
            return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The diary in chat. */
    public static void show(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        player.sendSystemMessage(Component.translatable("mcaconversations.diary.title").withStyle(ChatFormatting.GOLD,
                ChatFormatting.BOLD));
        for (Section section : sections(server, player)) {
            player.sendSystemMessage(Component.translatable("mcaconversations.diary." + section.key())
                    .withStyle(ChatFormatting.YELLOW));
            if (section.lines().isEmpty()) {
                player.sendSystemMessage(Component.literal("  ").append(Component.translatable("mcaconversations.diary.none"))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            for (Component line : section.lines()) {
                player.sendSystemMessage(Component.literal("  - ").append(line).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    /** The diary as a written book in the player's inventory. */
    public static void giveBook(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        List<Filterable<Component>> pages = new ArrayList<>();
        MutableComponent page = Component.empty();
        int lines = 0;
        for (Section section : sections(server, player)) {
            List<Component> block = new ArrayList<>();
            block.add(Component.translatable("mcaconversations.diary." + section.key()).withStyle(ChatFormatting.DARK_BLUE,
                    ChatFormatting.BOLD));
            if (section.lines().isEmpty()) {
                block.add(Component.translatable("mcaconversations.diary.none").withStyle(ChatFormatting.GRAY));
            }
            section.lines().forEach(l -> block.add(Component.literal("- ").append(l)));
            for (Component c : block) {
                int cost = 1 + c.getString().length() / 19;
                if (lines + cost > 13 && lines > 0) {
                    pages.add(Filterable.passThrough(page));
                    page = Component.empty();
                    lines = 0;
                }
                page.append(c).append("\n");
                lines += cost;
            }
        }
        if (lines > 0) {
            pages.add(Filterable.passThrough(page));
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(bookTitle(player.clientInformation().language())),
                player.getName().getString(), 0, pages, true));
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        player.displayClientMessage(Component.translatable("mcaconversations.diary.given").withStyle(ChatFormatting.GOLD), true);
    }
}
