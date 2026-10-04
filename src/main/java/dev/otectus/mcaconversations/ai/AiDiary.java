package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.gossip.GossipEvent;
import dev.otectus.mcaconversations.gossip.GossipSavedData;
import dev.otectus.mcaconversations.progress.AffectionMath;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /** One section: a heading and its lines. */
    record Section(String key, List<String> lines) {
    }

    private AiDiary() {
    }

    static List<Section> sections(MinecraftServer server, ServerPlayer player) {
        List<Section> out = new ArrayList<>();
        UUID me = player.getUUID();
        long gameNow = server.overworld().getGameTime();
        long day = AffectionMath.dayOf(gameNow);
        long now = server.overworld().getDayTime();
        Map<UUID, AiPairMemory> pairs = AiMemorySavedData.get(server).pairsOf(me);

        List<String> people = new ArrayList<>();
        pairs.entrySet().stream().filter(e -> !e.getValue().villagerName().isEmpty())
                .sorted(Comparator.comparingInt((Map.Entry<UUID, AiPairMemory> e) -> -e.getValue().lastHearts()))
                .limit(MAX_PEOPLE).forEach(e -> {
                    AiPairMemory p = e.getValue();
                    long ago = p.lastTalkDay() < 0 ? -1 : day - p.lastTalkDay();
                    people.add(p.villagerName() + ": " + p.lastHearts() + " hearts"
                            + (ago < 0 ? "" : ago == 0 ? ", talked today" : ", last talked " + ago + "d ago")
                            + (p.grudge(gameNow) ? " - holds a grudge" : ""));
                });
        out.add(new Section("people", people));

        List<String> promises = new ArrayList<>();
        pairs.values().forEach(p -> {
            p.promises().stream().filter(AiPromise::pending).forEach(pr -> promises.add("To " + p.villagerName() + ": "
                    + AiPromises.describe(pr) + (pr.dueDay() - day < 0 ? " (overdue!)" : pr.dueDay() == day ? " (today)"
                    : " (in " + (pr.dueDay() - day) + "d)")));
            p.wish(day).ifPresent(w -> promises.add(p.villagerName() + " would love "
                    + AiContextFormat.words(w.item().replace("#", ""))));
        });
        out.add(new Section("promises", promises));

        List<String> dates = new ArrayList<>();
        for (AiLivesSavedData.Date d : AiLivesSavedData.get(server).dates()) {
            if (d.player.equals(me) && d.state != AiLivesSavedData.Date.State.DONE) {
                long in = d.start - now;
                dates.add("A date with " + d.villagerName + (d.place.isEmpty() ? "" : " at the " + d.place) + " - "
                        + (d.state == AiLivesSavedData.Date.State.ON ? "now" : in <= 0 ? "they are waiting for you!"
                        : in < 2_000 ? "very soon" : Math.floorDiv(d.start, 24_000L) == Math.floorDiv(now, 24_000L)
                        ? "this evening" : "tomorrow evening"));
            }
        }
        out.add(new Section("dates", dates));

        List<String> village = new ArrayList<>();
        AiVillageLifeSavedData life = AiVillageLifeSavedData.get(server);
        for (AiVillageEvent e : life.events()) {
            if (!e.ended && e.type.gathering()) {
                village.add(capitalise(e.describe()) + " - " + e.when(now)
                        + (e.attended.contains(me) ? " (you went)" : ""));
            }
        }
        long today = Math.floorDiv(now, AiVillageEventType.DAY);
        for (AiVillageLifeSavedData.Census c : life.censuses().values()) {
            if (c.leader != null) {
                village.add("Leader: " + c.leaderName + " (" + AiPolitics.platformWords(c.leaderPlatform) + ")");
            }
            if (c.electionPending()) {
                UUID backed = c.backers.get(me);
                village.add("Election in " + Math.max(0, c.nextElectionDay - today) + "d: " + c.candidateNames.get(0) + " vs "
                        + c.candidateNames.get(1) + (backed == null ? "" : " - you back "
                        + c.candidateNames.get(c.candidates.indexOf(backed))));
            }
            int kills = c.defenders.getOrDefault(me, 0);
            if (kills > 0) {
                village.add("You have killed " + kills + " monsters defending a village");
            }
        }
        out.add(new Section("village", village));

        List<String> ties = new ArrayList<>();
        for (AiVillageEvent e : life.events()) {
            if (e.type == AiVillageEventType.QUARREL && e.started) {
                ties.add(capitalise(e.describe()));
            }
        }
        pairs.forEach((id, p) -> {
            if (!p.secrets().isEmpty()) {
                ties.add(p.villagerName() + " trusted you with a secret");
            }
            AiLivesSavedData.get(server).childhood(id, me).ifPresent(c -> {
                String tone = AiChildhood.tone(c.score);
                if (!tone.isEmpty()) {
                    ties.add(p.villagerName() + " remembers you as " + tone + " from their childhood");
                }
            });
        });
        out.add(new Section("ties", ties));

        List<String> rumours = new ArrayList<>();
        GossipSavedData.get(server).log().events().stream()
                .filter(e -> e.type().aboutListener() && me.equals(e.aUuid()) && gameNow - e.created() <= 24_000L * 7)
                .sorted(Comparator.comparingLong(GossipEvent::created).reversed()).limit(5)
                .forEach(e -> rumours.add("People say you were " + (e.type().name().endsWith("KINDNESS") ? "kind" : "cruel")
                        + " to " + e.bName()));
        out.add(new Section("rumours", rumours));
        return out;
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
            for (String line : section.lines()) {
                player.sendSystemMessage(Component.literal("  - " + line).withStyle(ChatFormatting.GRAY));
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
            section.lines().forEach(l -> block.add(Component.literal("- " + l)));
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
                Filterable.passThrough(Component.translatable("mcaconversations.diary.book").getString()),
                player.getName().getString(), 0, pages, true));
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        player.displayClientMessage(Component.translatable("mcaconversations.diary.given").withStyle(ChatFormatting.GOLD), true);
    }
}
