package dev.otectus.mcaconversations.ai;

import dev.otectus.mcaconversations.compat.ReputationBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * What an AI conversation tells MCA: Reputation, when it is installed. Only incident definitions
 * MCA: Reputation ships are named (promise_made, promise_kept, promise_broken, public_apology), and
 * every signal goes through {@link ReputationBridge#recordSignal}, which is a no-op without the mod.
 * Decision ids carry the villager and the promise, because Reputation deduplicates by decision.
 */
final class AiReputationLink {

    static final String PROMISE_MADE = "mcareputation:promise_made";
    static final String PROMISE_KEPT = "mcareputation:promise_kept";
    static final String PROMISE_BROKEN = "mcareputation:promise_broken";
    static final String PUBLIC_APOLOGY = "mcareputation:public_apology";

    private AiReputationLink() {
    }

    static void promise(ServerPlayer player, Entity villager, AiPromise promise, String incident, String phase) {
        String decision = "ai.promise." + villager.getUUID() + "." + promise.id() + "." + phase;
        String visibility = PROMISE_MADE.equals(incident) ? "private" : "witnessed";
        ReputationBridge.recordSignal(player, villager, ReputationBridge.SignalRequest.of(incident, visibility, decision));
    }

    /** An accepted apology amends the newest negative incident this villager knows of, if any. */
    static void apology(ServerPlayer player, Entity villager, long gameTime) {
        String decision = "ai.apology." + villager.getUUID() + "." + (gameTime / 24000L);
        ReputationBridge.recordSignal(player, villager, new ReputationBridge.SignalRequest(PUBLIC_APOLOGY, "witnessed",
                decision, true, List.of(), 0L, "", 0L));
    }
}
