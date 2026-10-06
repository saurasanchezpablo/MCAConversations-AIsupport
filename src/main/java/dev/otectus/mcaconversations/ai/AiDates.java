package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.conversation.DepthClass;
import dev.otectus.mcaconversations.progress.AffectionMath;
import dev.otectus.mcaconversations.progress.ReplayPolicy;
import dev.otectus.mcaconversations.state.ConversationState;
import dev.otectus.mcaconversations.state.StateTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dates. A villager the player is courting can agree to meet them, now or in the evening, somewhere
 * in the village or right where they stand. Near the time, the villager walks there and waits. If the
 * player comes, the date is on: the villager stays close, every conversation knows it is a date, and
 * everything said counts for more. Afterwards the villager remembers how it went: lovely, awkward or
 * bad. If the player never comes, they were stood up, and they will not forget it.
 *
 * <p>Times follow the day clock. Server thread only.
 */
final class AiDates {

    /** How long a villager waits at the spot for the player. */
    static final long WAIT_TICKS = 2_400;
    /** How long a date lasts once the player arrives. */
    static final long DATE_TICKS = 3_000;
    static final double ARRIVE = 6.0;
    static final double WANDER_OFF = 40.0;
    static final int GOOD_HEARTS = 2;
    static final int BAD_HEARTS = -2;
    static final int STOOD_UP_HEARTS = -3;

    private AiDates() {
    }

    /**
     * When a date asked for at {@code now} (day time) starts: {@code 0} now, {@code 1} this evening
     * (tomorrow if the evening is over), {@code 2} tomorrow evening. Pure.
     */
    static long startFor(int when, long now) {
        long dayStart = Math.floorDiv(now, AiVillageEventType.DAY) * AiVillageEventType.DAY;
        long evening = dayStart + 11_000;
        return switch (when) {
            case 0 -> now + 200;
            case 2 -> evening + AiVillageEventType.DAY;
            default -> now < evening + 1_500 ? Math.max(evening, now + 200) : evening + AiVillageEventType.DAY;
        };
    }

    /** The villager said yes. */
    static void agree(Entity villager, ServerPlayer player, String villagerName, AiEffect.Action action, AiSocial.Turn turn,
                      long gameNow) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        long now = server.overworld().getDayTime();
        BlockPos spot = player.blockPosition();
        String label = "";
        Component shown = Component.translatable("mcaconversations.ai.date.here");
        AiSocial.Place place = action.place().isEmpty() ? null : turn.places().get(action.place());
        if (place != null && place.building().isPresent()) {
            spot = AiVillageEvents.ground(player.serverLevel(), place.building().get());
            label = place.label();
            // MCA names its building types in every language it ships; the English label is the fallback.
            shown = Component.translatableWithFallback("buildingType." + place.token(), label);
        }
        long start = startFor(action.amount(), now);
        AiLivesSavedData data = AiLivesSavedData.get(server);
        data.addDate(new AiLivesSavedData.Date(villager.getUUID(), player.getUUID(), villagerName, spot, label, start));
        String when = when(start, now);
        String whenKey = when.equals("now") ? "now" : when.startsWith("tomorrow") ? "tomorrow" : "evening";
        player.displayClientMessage(Component.translatable("mcaconversations.ai.date.set", villagerName,
                Component.translatable("mcaconversations.ai.date.when." + whenKey),
                shown)
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        remember(server, villager.getUUID(), player, "I agreed to go on a date with " + player.getName().getString() + " "
                + when + (label.isEmpty() ? "" : " at the " + label) + ".", AiImportance.MEDIUM, AiSentiment.POSITIVE);
    }

    private static String when(long start, long now) {
        if (start - now <= 400) {
            return "now";
        }
        return Math.floorDiv(start, AiVillageEventType.DAY) == Math.floorDiv(now, AiVillageEventType.DAY)
                ? "this evening" : "tomorrow evening";
    }

    // --- every second ----------------------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        AiLivesSavedData data = AiLivesSavedData.get(server);
        if (data.dates().isEmpty()) {
            return;
        }
        long now = server.overworld().getDayTime();
        for (AiLivesSavedData.Date date : List.copyOf(data.dates())) {
            if (date.state == AiLivesSavedData.Date.State.DONE) {
                continue;
            }
            step(server, data, date, now);
        }
        if (data.dates().removeIf(d -> d.state == AiLivesSavedData.Date.State.DONE
                && now - d.stateSince > AiVillageEventType.DAY * 2L)) {
            data.changed();
        }
    }

    private static void step(MinecraftServer server, AiLivesSavedData data, AiLivesSavedData.Date date, long now) {
        ServerPlayer player = server.getPlayerList().getPlayer(date.player);
        Entity villager = find(server, date.villager);
        if (villager != null && !villager.isAlive()) {
            close(data, date, now);
            return;
        }
        switch (date.state) {
            case PLANNED -> {
                if (now >= date.start - 600 && villager != null) {
                    AiVillageEvents.steer(villager, date.spot, date.spot);
                }
                if (now >= date.start) {
                    date.state = AiLivesSavedData.Date.State.WAITING;
                    date.stateSince = now;
                    data.changed();
                }
            }
            case WAITING -> {
                if (villager != null) {
                    AiVillageEvents.steer(villager, date.spot, date.spot);
                }
                if (player != null && villager != null && player.level() == villager.level()
                        && (player.distanceTo(villager) <= ARRIVE || player.blockPosition().closerThan(date.spot, ARRIVE))) {
                    date.state = AiLivesSavedData.Date.State.ON;
                    date.stateSince = now;
                    data.changed();
                    player.displayClientMessage(Component.translatable("mcaconversations.ai.date.begun", date.villagerName)
                            .withStyle(ChatFormatting.LIGHT_PURPLE), true);
                } else if (now - date.stateSince > WAIT_TICKS) {
                    stoodUp(server, player, villager, date);
                    close(data, date, now);
                }
            }
            case ON -> {
                boolean away = player == null || villager == null || player.level() != villager.level()
                        || player.distanceTo(villager) > WANDER_OFF;
                if (away || now - date.stateSince > DATE_TICKS) {
                    judge(server, player, villager, date);
                    close(data, date, now);
                    return;
                }
                if (villager instanceof Mob mob) {
                    if (mob.distanceTo(player) > 4) {
                        AiVillageEvents.steer(mob, player.blockPosition(), player.blockPosition());
                    } else {
                        mob.getNavigation().stop();
                        mob.getLookControl().setLookAt(player);
                    }
                }
            }
            default -> {
            }
        }
    }

    private static void close(AiLivesSavedData data, AiLivesSavedData.Date date, long now) {
        date.state = AiLivesSavedData.Date.State.DONE;
        date.stateSince = now;
        data.changed();
    }

    private static Entity find(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static void stoodUp(MinecraftServer server, ServerPlayer player, Entity villager, AiLivesSavedData.Date date) {
        String playerName = player != null ? player.getName().getString() : "they";
        String where = date.place.isEmpty() ? "" : " at the " + date.place;
        AiMemorySavedData.get(server).edit(date.villager, date.player).remember(new AiMemoryNote(playerName
                        + " stood me up" + where + ". I waited and they never came.", AiImportance.HIGH),
                AiSentiment.STRONGLY_NEGATIVE, day(server), cap());
        if (player != null && villager != null) {
            hearts(server, villager, player, STOOD_UP_HEARTS, "ai.date.stood", date.start);
            StateTracker.apply(villager, player, ConversationState.ANNOYED);
            player.displayClientMessage(Component.translatable("mcaconversations.ai.date.stood_up", date.villagerName)
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    /** How it went: by the warmth of what was said on the date. */
    private static void judge(MinecraftServer server, ServerPlayer player, Entity villager, AiLivesSavedData.Date date) {
        String playerName = player != null ? player.getName().getString() : "they";
        String where = date.place.isEmpty() ? "" : " at the " + date.place;
        Outcome outcome = outcome(date.turns, date.warm, date.cold);
        String note = switch (outcome) {
            case LOVELY -> "We had a lovely date" + where + ", " + playerName + " and I.";
            case BAD -> "Our date" + where + " went badly. " + playerName + " was not kind.";
            case AWKWARD -> playerName + " came to our date" + where + ", but we hardly talked.";
        };
        AiMemorySavedData.get(server).edit(date.villager, date.player).remember(new AiMemoryNote(note,
                        outcome == Outcome.AWKWARD ? AiImportance.MEDIUM : AiImportance.HIGH),
                outcome == Outcome.LOVELY ? AiSentiment.STRONGLY_POSITIVE : outcome == Outcome.BAD
                        ? AiSentiment.NEGATIVE : AiSentiment.NEUTRAL, day(server), cap());
        if (player == null || villager == null) {
            return;
        }
        switch (outcome) {
            case LOVELY -> {
                hearts(server, villager, player, GOOD_HEARTS, "ai.date.good", date.start);
                StateTracker.apply(villager, player, ConversationState.SMITTEN);
            }
            case BAD -> {
                hearts(server, villager, player, BAD_HEARTS, "ai.date.bad", date.start);
                StateTracker.apply(villager, player, ConversationState.ANNOYED);
            }
            default -> {
            }
        }
        player.displayClientMessage(Component.translatable("mcaconversations.ai.date.over." + outcome.key(),
                date.villagerName).withStyle(ChatFormatting.LIGHT_PURPLE), true);
    }

    enum Outcome {
        LOVELY, AWKWARD, BAD;

        String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** How a date went from what was said on it. Pure. */
    static Outcome outcome(int turns, int warm, int cold) {
        if (cold > warm) {
            return Outcome.BAD;
        }
        return turns >= 2 && warm > cold ? Outcome.LOVELY : Outcome.AWKWARD;
    }

    /** One payout per outcome kind and day (a fixed decision id, so dates never fill the once-ever ledger). */
    private static void hearts(MinecraftServer server, Entity villager, ServerPlayer player, int delta, String kind,
                               long start) {
        if (McaConversationsConfig.aiRelationshipEffects()) {
            AiHearts.grant(server, villager, player, kind, delta, DepthClass.STANDARD, ReplayPolicy.ONCE_PER_DAY, 0, 0,
                    kind + "." + start + "." + villager.getUUID(), villager.level().getGameTime());
        }
    }

    private static void remember(MinecraftServer server, UUID villager, ServerPlayer player, String text,
                                 AiImportance importance, AiSentiment sentiment) {
        AiMemorySavedData.get(server).edit(villager, player.getUUID()).remember(new AiMemoryNote(text, importance),
                sentiment, day(server), cap());
    }

    private static long day(MinecraftServer server) {
        return AffectionMath.dayOf(server.overworld().getGameTime());
    }

    private static int cap() {
        return Math.max(1, McaConversationsConfig.aiMemoriesPerPair());
    }

    // --- what the rest of the mod asks ------------------------------------------------------------------

    /** A conversation turn finished with this villager: on a date, it counts towards how it went. */
    static void onTurn(MinecraftServer server, UUID villager, UUID player, AiSentiment sentiment) {
        AiLivesSavedData data = AiLivesSavedData.get(server);
        data.date(villager, player).filter(d -> d.state == AiLivesSavedData.Date.State.ON).ifPresent(d -> {
            d.turns++;
            if (sentiment.positive()) {
                d.warm++;
            } else if (sentiment.negative()) {
                d.cold++;
            }
            data.changed();
        });
    }

    static boolean onDate(MinecraftServer server, UUID villager, UUID player) {
        return AiLivesSavedData.get(server).date(villager, player)
                .map(d -> d.state == AiLivesSavedData.Date.State.ON).orElse(false);
    }

    /** Whether this villager has a date coming up or under way with this player (the bubble shows a heart). */
    static boolean pending(MinecraftServer server, UUID villager, UUID player) {
        return AiLivesSavedData.get(server).date(villager, player).isPresent();
    }

    static List<String> promptLines(MinecraftServer server, UUID villager, ServerPlayer player) {
        List<String> out = new ArrayList<>();
        long now = server.overworld().getDayTime();
        Optional<AiLivesSavedData.Date> date = AiLivesSavedData.get(server).date(villager, player.getUUID());
        if (date.isEmpty()) {
            return out;
        }
        AiLivesSavedData.Date d = date.get();
        String name = player.getName().getString();
        String where = d.place.isEmpty() ? "" : " at the " + d.place;
        switch (d.state) {
            case PLANNED -> out.add("You agreed to a date with " + name + " " + when(d.start, now) + where
                    + ". You are looking forward to it (and a little nervous).");
            case WAITING -> out.add("You are waiting for " + name + " for your date" + where + ". Are they coming?");
            case ON -> out.add("You are ON A DATE with " + name + where + " right now. It matters to you: be yourself, "
                    + "be romantic if it feels right, and notice how they treat you.");
            default -> {
            }
        }
        return out;
    }
}
