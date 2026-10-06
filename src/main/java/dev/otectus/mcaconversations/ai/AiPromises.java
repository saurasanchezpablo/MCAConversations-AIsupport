package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.McaConversationsConfig;
import dev.otectus.mcaconversations.compat.McaCompat;
import dev.otectus.mcaconversations.conversation.DepthClass;
import dev.otectus.mcaconversations.disposition.DispositionApply;
import dev.otectus.mcaconversations.disposition.DispositionAxis;
import dev.otectus.mcaconversations.disposition.Dispositions;
import dev.otectus.mcaconversations.progress.ReplayPolicy;
import dev.otectus.mcaconversations.state.ConversationState;
import dev.otectus.mcaconversations.state.StateTracker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * Promises and wishes over time. A promise is made in conversation and judged later, by what the
 * player actually does: gifts count toward an item promise (several small gifts add up), talking to
 * the villager on or after the day keeps a promise to come back, and a promise still open a day after
 * it was due is broken. A wish is fulfilled by a gift of the wished-for item. Each outcome is felt
 * once (settling is one-way): hearts through the guarded path (one payout of each kind per day, so
 * promises never fill the pair's once-ever ledger), a lingering mood, a memory the villager will
 * bring up, and, with MCA: Reputation, a deed the village hears about.
 */
final class AiPromises {

    static final int KEPT_HEARTS = 3;
    static final int VISIT_KEPT_HEARTS = 1;
    static final int BROKEN_HEARTS = -3;
    static final int WISH_HEARTS = 2;

    private AiPromises() {
    }

    /** True when the item id or tag names something that exists, so a promise can ever be kept. */
    static boolean itemExists(String ref) {
        if (ref == null || ref.isEmpty()) {
            return false;
        }
        boolean tag = ref.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(tag ? ref.substring(1) : ref);
        if (id == null) {
            return false;
        }
        return tag ? BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id)).isPresent()
                : BuiltInRegistries.ITEM.containsKey(id);
    }

    static boolean matches(String ref, ItemStack stack) {
        if (ref == null || ref.isEmpty() || stack == null || stack.isEmpty()) {
            return false;
        }
        if (ref.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(ref.substring(1));
            return id != null && stack.is(TagKey.create(Registries.ITEM, id));
        }
        return ref.equals(String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
    }

    /**
     * Judges this pair's promises at the start of a conversation: a promise to come back is kept by
     * coming back on time, and anything left open past its grace day is broken. Server thread.
     */
    static void sweep(MinecraftServer server, Entity villager, ServerPlayer player, long now, long day) {
        AiPairMemory pair = AiMemorySavedData.get(server).peek(villager.getUUID(), player.getUUID()).orElse(null);
        if (pair == null || pair.openPromises() == 0) {
            return;
        }
        AiMemorySavedData data = AiMemorySavedData.get(server);
        pair = data.edit(villager.getUUID(), player.getUUID());
        for (AiPromise promise : pair.promises()) {
            if (!promise.pending()) {
                continue;
            }
            if (promise.isVisit() && day >= promise.dueDay() && !promise.overdue(day)) {
                AiPromise kept = promise.settle(AiPromise.State.KEPT, day);
                pair.updatePromise(kept);
                kept(server, villager, player, kept, VISIT_KEPT_HEARTS, now, day, false);
            } else if (promise.overdue(day)) {
                AiPromise broken = promise.settle(AiPromise.State.BROKEN, day);
                pair.updatePromise(broken);
                broken(server, villager, player, broken, now, day);
            }
        }
    }

    /** A gift was accepted: count it toward item promises, and see whether it is a wish come true. */
    static void onGift(MinecraftServer server, Entity villager, ServerPlayer player, ItemStack stack, long now, long day) {
        AiMemorySavedData data = AiMemorySavedData.get(server);
        AiPairMemory pair = data.peek(villager.getUUID(), player.getUUID()).orElse(null);
        if (pair == null) {
            return;
        }
        String name = McaCompat.getVillagerName(villager).orElse(villager.getName().getString());
        for (AiPromise promise : pair.promises()) {
            if (promise.pending() && !promise.isVisit() && matches(promise.item(), stack)) {
                if (promise.overdue(day)) {
                    // Too late: it was broken before this gift came; the gift may still count elsewhere.
                    AiPromise broken = promise.settle(AiPromise.State.BROKEN, day);
                    data.edit(villager.getUUID(), player.getUUID()).updatePromise(broken);
                    broken(server, villager, player, broken, now, day);
                    continue;
                }
                AiPromise updated = promise.deliver(stack.getCount(), day);
                data.edit(villager.getUUID(), player.getUUID()).updatePromise(updated);
                if (updated.state() == AiPromise.State.KEPT) {
                    kept(server, villager, player, updated, KEPT_HEARTS, now, day, true);
                } else {
                    AiLines.say(villager, player, AiLines.variant("promise_progress", updated.delivered(), updated.count()), name);
                }
                return; // one gift settles at most one promise
            }
        }
        pair.wish(day).filter(w -> matches(w.item(), stack)).ifPresent(w -> {
            AiPairMemory edit = data.edit(villager.getUUID(), player.getUUID());
            edit.setWish(null);
            if (McaConversationsConfig.aiRelationshipEffects()) {
                AiHearts.grant(server, villager, player, "ai.wish", WISH_HEARTS, DepthClass.STANDARD,
                        ReplayPolicy.ONCE_PER_DAY, 0, 0, "ai.wish." + w.createdDay() + "@" + now, now);
            }
            StateTracker.apply(villager, player, ConversationState.GRATEFUL);
            edit.remember(new AiMemoryNote(player.getName().getString() + " remembered I wanted "
                    + AiContextFormat.words(w.item().replace("#", "")) + " and brought it to me.", AiImportance.HIGH),
                    AiSentiment.STRONGLY_POSITIVE, day, McaConversationsConfig.aiMemoriesPerPair());
            AiLines.say(villager, player, AiLines.variant("wish_fulfilled"), name, AiEmotion.GRATEFUL,
                    dev.otectus.mcaconversations.voice.VoiceIntent.THANK);
        });
    }

    private static void kept(MinecraftServer server, Entity villager, ServerPlayer player, AiPromise promise, int hearts,
                             long now, long day, boolean speak) {
        try {
            if (McaConversationsConfig.aiRelationshipEffects()) {
                AiHearts.grant(server, villager, player, "ai.promise.kept", hearts, DepthClass.STANDARD,
                        ReplayPolicy.ONCE_PER_DAY, 0, 0, "ai.promise.kept." + promise.id() + "@" + now, now);
            }
            StateTracker.apply(villager, player, ConversationState.GRATEFUL);
            Dispositions.apply(villager, player, new DispositionApply("ai_promise", Map.of(DispositionAxis.TRUST, 3)));
            AiMemorySavedData.get(server).edit(villager.getUUID(), player.getUUID()).remember(
                    new AiMemoryNote(player.getName().getString() + " kept their promise: " + describe(promise) + ".",
                            AiImportance.HIGH), AiSentiment.STRONGLY_POSITIVE, day, McaConversationsConfig.aiMemoriesPerPair());
            AiReputationLink.promise(player, villager, promise, AiReputationLink.PROMISE_KEPT, "kept");
            if (speak) {
                String name = McaCompat.getVillagerName(villager).orElse(villager.getName().getString());
                AiLines.say(villager, player, AiLines.variant("promise_kept"), name, AiEmotion.GRATEFUL,
                        dev.otectus.mcaconversations.voice.VoiceIntent.THANK);
            }
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("AI promise-kept effects failed", t);
        }
    }

    private static void broken(MinecraftServer server, Entity villager, ServerPlayer player, AiPromise promise,
                               long now, long day) {
        try {
            if (McaConversationsConfig.aiRelationshipEffects()) {
                AiHearts.grant(server, villager, player, "ai.promise.broken", BROKEN_HEARTS, DepthClass.STANDARD,
                        ReplayPolicy.ONCE_PER_DAY, 0, 0, "ai.promise.broken." + promise.id() + "@" + now, now);
            }
            StateTracker.apply(villager, player, ConversationState.ANNOYED);
            Dispositions.apply(villager, player, new DispositionApply("ai_promise",
                    Map.of(DispositionAxis.TRUST, -4, DispositionAxis.TENSION, 3)));
            AiMemorySavedData.get(server).edit(villager.getUUID(), player.getUUID()).remember(
                    new AiMemoryNote(player.getName().getString() + " broke their promise: " + describe(promise) + ".",
                            AiImportance.HIGH), AiSentiment.NEGATIVE, day, McaConversationsConfig.aiMemoriesPerPair());
            AiReputationLink.promise(player, villager, promise, AiReputationLink.PROMISE_BROKEN, "broken");
        } catch (Throwable t) {
            McaConversations.LOGGER.debug("AI promise-broken effects failed", t);
        }
    }

    static String describe(AiPromise promise) {
        if (!promise.summary().isEmpty()) {
            return promise.summary();
        }
        return promise.isVisit() ? "to come back" : "to bring " + promise.count() + " "
                + AiContextFormat.words(promise.item().replace("#", ""));
    }
}
