package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.context.ContextKey;
import dev.otectus.mcaconversations.context.ContextKeys;
import dev.otectus.mcaconversations.context.ContextRequest;
import dev.otectus.mcaconversations.context.ContextSources;
import dev.otectus.mcaconversations.context.ConversationContextSnapshot;
import dev.otectus.mcaconversations.conversation.RelationshipBand;
import dev.otectus.mcaconversations.conversation.Relationships;
import dev.otectus.mcaconversations.disposition.DispositionAxis;
import dev.otectus.mcaconversations.disposition.Dispositions;
import dev.otectus.mcaconversations.state.ConversationState;
import dev.otectus.mcaconversations.state.MemoryIds;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Gathers the structured context for one turn from systems this mod already maintains, on the server
 * thread: the typed {@link ConversationContextSnapshot} every scripted conversation reads, the
 * relationship band, the disposition vector and the lingering conversation states. MCA's own prompt
 * modules already describe personality, traits, family ties and the village, so those are not
 * repeated here; this adds what MCA's prompt cannot know.
 *
 * <p>Only known values are included, each section is a handful of lines, and nothing about other
 * players is included: the context describes this villager, this player and their shared situation.
 */
public final class AiContextCollector {

    /** The purpose this mod's context sources see for an AI turn. */
    static final String PURPOSE = "ai_chat";

    private AiContextCollector() {
    }

    public static List<AiContextSection> collect(Entity villager, ServerPlayer player, String villagerName,
                                                 String playerName, int aiTurns, long lastAiTalkDay, long today) {
        ConversationContextSnapshot snapshot;
        try {
            snapshot = ContextSources.capture(ContextRequest.of(villager, player, PURPOSE));
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("AI context capture failed; continuing without it", t);
            snapshot = ConversationContextSnapshot.EMPTY;
        }
        List<AiContextSection> sections = new ArrayList<>();
        sections.add(relationship(snapshot, villager, player, villagerName, playerName, aiTurns, lastAiTalkDay, today));
        sections.add(self(snapshot, villagerName));
        sections.add(situation(snapshot));
        sections.add(reputation(snapshot, villagerName, playerName));
        return sections;
    }

    private static AiContextSection relationship(ConversationContextSnapshot s, Entity villager, ServerPlayer player,
                                                 String villagerName, String playerName, int aiTurns,
                                                 long lastAiTalkDay, long today) {
        List<String> lines = new ArrayList<>();
        RelationshipBand band = safeBand(villager, player);
        if (band != null) {
            lines.add("Relationship: " + band.key() + " (MCA hearts " + McaCompat.getHearts(player, villager) + ")");
        }
        List<String> roles = new ArrayList<>();
        flag(s, ContextKeys.PLAYER_IS_SPOUSE).ifPresent(v -> roles.add("spouse"));
        flag(s, ContextKeys.PLAYER_IS_PARENT).ifPresent(v -> roles.add(playerName + " is " + villagerName + "'s parent"));
        flag(s, ContextKeys.PLAYER_IS_CHILD).ifPresent(v -> roles.add(playerName + " is " + villagerName + "'s child"));
        flag(s, ContextKeys.PLAYER_IS_SIBLING).ifPresent(v -> roles.add("siblings"));
        if (!roles.isEmpty()) {
            lines.add("Family: " + String.join("; ", roles));
        }
        s.value(ContextKeys.SOCIAL_CONTACT).ifPresent(v -> lines.add("Familiarity: " + AiContextFormat.words(v)));
        s.value(ContextKeys.TIME_DAYS_SINCE_FIRST_MET)
                .ifPresent(v -> lines.add("First met: " + AiContextFormat.daysAgo(v)));
        s.value(ContextKeys.TIME_DAYS_SINCE_LAST_TALK)
                .ifPresent(v -> lines.add("Last spoke: " + AiContextFormat.daysAgo(v)));
        if (aiTurns > 0) {
            lines.add("Earlier free conversations: " + aiTurns + " exchanges, the last "
                    + AiContextFormat.daysAgo(lastAiTalkDay < 0 ? -1 : today - lastAiTalkDay));
        }
        flag(s, ContextKeys.NARRATIVE_RUPTURE).ifPresent(v ->
                lines.add("There is an unresolved falling-out between " + villagerName + " and " + playerName + "."));
        s.value(ContextKeys.NARRATIVE_DUE_COMMITMENTS).filter(l -> !l.isEmpty())
                .ifPresent(v -> lines.add("Promises due: " + AiContextFormat.list(v)));
        if (Dispositions.enabled()) {
            List<String> feelings = new ArrayList<>();
            for (DispositionAxis axis : AiReplyParser.NUDGEABLE_AXES) {
                feelings.add(axis.key() + " " + AiContextFormat.dispositionBand(axis, Dispositions.axis(villager, player, axis)));
            }
            lines.add("Feelings toward " + playerName + ": " + String.join(", ", feelings));
        }
        List<String> states = new ArrayList<>();
        for (ConversationState state : ConversationState.values()) {
            String id = state.playerScoped() ? MemoryIds.playerScoped(state.memoryId(), player.getUUID()) : state.memoryId();
            if (McaCompat.hasMemory(villager, id)) {
                states.add(state.jsonName());
            }
        }
        if (!states.isEmpty()) {
            lines.add("Lingering mood" + (states.size() > 1 ? "s" : "") + ": " + String.join(", ", states));
        }
        return new AiContextSection("Relationship with " + playerName, lines);
    }

    private static AiContextSection self(ConversationContextSnapshot s, String villagerName) {
        List<String> lines = new ArrayList<>();
        s.value(ContextKeys.SPEAKER_MOOD).ifPresent(v -> lines.add("Mood: " + AiContextFormat.words(v)));
        s.value(ContextKeys.SPEAKER_HEALTH_BAND).ifPresent(v -> lines.add("Health: " + AiContextFormat.words(v)));
        s.value(ContextKeys.WORK_ACTIVITY).ifPresent(v -> lines.add("Doing: " + AiContextFormat.words(v)));
        s.value(ContextKeys.WORK_CHORE).ifPresent(v -> lines.add("Assigned chore: " + AiContextFormat.words(v)));
        set(s, ContextKeys.IDENTITY_INTERESTS).ifPresent(v -> lines.add("Interests: " + v));
        set(s, ContextKeys.IDENTITY_VALUES).ifPresent(v -> lines.add("Values: " + v));
        s.value(ContextKeys.IDENTITY_COMFORT).ifPresent(v -> lines.add("Finds comfort in: " + AiContextFormat.words(v)));
        s.value(ContextKeys.IDENTITY_AVERSION).ifPresent(v -> lines.add("Dislikes: " + AiContextFormat.words(v)));
        s.value(ContextKeys.IDENTITY_SOCIAL_STYLE).ifPresent(v -> lines.add("Social style: " + AiContextFormat.words(v)));
        s.value(ContextKeys.IDENTITY_DISCLOSURE_STYLE)
                .ifPresent(v -> lines.add("Opens up: " + AiContextFormat.words(v)));
        s.value(ContextKeys.IDENTITY_FORMATIVE_EVENT)
                .ifPresent(v -> lines.add("Formative experience: " + AiContextFormat.words(v)));
        return new AiContextSection(villagerName + " right now", lines);
    }

    private static AiContextSection situation(ConversationContextSnapshot s) {
        List<String> lines = new ArrayList<>();
        s.value(ContextKeys.TIME_BAND).ifPresent(v -> lines.add("Time: " + AiContextFormat.words(v)));
        s.value(ContextKeys.TIME_SEASON).ifPresent(v -> lines.add("Season: " + AiContextFormat.words(v)));
        s.value(ContextKeys.TIME_HOLIDAY).ifPresent(v -> lines.add("Holiday: " + AiContextFormat.words(v)));
        s.value(ContextKeys.WEATHER_STATE).ifPresent(v -> lines.add("Weather: " + AiContextFormat.words(v)));
        s.value(ContextKeys.PLACE_LOCATION).ifPresent(v -> lines.add("Where: " + AiContextFormat.words(v)));
        s.value(ContextKeys.PLACE_VILLAGE_NAME).ifPresent(v -> lines.add("Village: " + v));
        s.value(ContextKeys.VILLAGE_RECENT_EVENT).ifPresent(v -> lines.add("Recent village news: " + AiContextFormat.words(v)));
        s.value(ContextKeys.SOCIAL_NEARBY).filter(l -> !l.isEmpty())
                .ifPresent(v -> lines.add("Others nearby: " + AiContextFormat.list(v)));
        s.value(ContextKeys.NARRATIVE_RECENT_SUBJECTS).filter(l -> !l.isEmpty())
                .ifPresent(v -> lines.add("Recently talked about: " + AiContextFormat.list(v)));
        return new AiContextSection("Situation", lines);
    }

    private static AiContextSection reputation(ConversationContextSnapshot s, String villagerName, String playerName) {
        List<String> lines = new ArrayList<>();
        if (flag(s, ContextKeys.STANDING_SPEAKER_KNOWS_PLAYER).isPresent()) {
            s.value(ContextKeys.STANDING_SPEAKER_RECOGNITION_TIER)
                    .ifPresent(v -> lines.add(villagerName + " recognises " + playerName + ": " + AiContextFormat.words(v)));
            set(s, ContextKeys.STANDING_SPEAKER_KNOWN_FOR).ifPresent(v -> lines.add("Known for: " + v));
        }
        flag(s, ContextKeys.CRIME_WANTED).ifPresent(v -> lines.add(playerName + " is wanted by the law"));
        return new AiContextSection("What " + villagerName + " has heard about " + playerName, lines);
    }

    private static RelationshipBand safeBand(Entity villager, ServerPlayer player) {
        try {
            return Relationships.bandOf(villager, player);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Present only when the flag is known and true. */
    private static Optional<Boolean> flag(ConversationContextSnapshot s, ContextKey<Boolean> key) {
        return s.value(key).filter(Boolean::booleanValue);
    }

    private static Optional<String> set(ConversationContextSnapshot s, ContextKey<? extends Collection<?>> key) {
        return s.value(key).map(AiContextFormat::list).filter(v -> !v.isEmpty());
    }
}
