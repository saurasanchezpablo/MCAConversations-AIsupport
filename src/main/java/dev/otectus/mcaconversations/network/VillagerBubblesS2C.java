package dev.otectus.mcaconversations.network;

import dev.otectus.mcaconversations.McaConversations;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which villagers around this player have something to tell them, and how much it matters, so the
 * client can draw a small bubble over their heads (protocol 5). The whole set is sent each time it
 * changes; an empty map clears every bubble.
 *
 * @param bubbles villager to {@link #NEWS}, {@link #URGENT} or {@link #EVENT}
 */
public record VillagerBubblesS2C(Map<UUID, Byte> bubbles) implements CustomPacketPayload {

    /** Something to tell: a rumour, missing the player, a wish. Drawn as "…". */
    public static final byte NEWS = 0;
    /** Something that matters: a promise due or kept, a loss. Drawn as "!". */
    public static final byte URGENT = 1;
    /** An invitation to a village event. Drawn as "♪". */
    public static final byte EVENT = 2;
    /** Most bubbles one message carries. */
    public static final int MAX = 32;

    public static final CustomPacketPayload.Type<VillagerBubblesS2C> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(McaConversations.MOD_ID, "villager_bubbles"));
    public static final StreamCodec<FriendlyByteBuf, VillagerBubblesS2C> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                int n = Math.min(MAX, p.bubbles.size());
                buf.writeVarInt(n);
                p.bubbles.entrySet().stream().limit(n).forEach(e -> {
                    buf.writeUUID(e.getKey());
                    buf.writeByte(e.getValue());
                });
            },
            buf -> {
                int n = Math.min(MAX, buf.readVarInt());
                Map<UUID, Byte> out = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    out.put(buf.readUUID(), buf.readByte());
                }
                return new VillagerBubblesS2C(out);
            });

    public VillagerBubblesS2C {
        bubbles = bubbles == null ? Map.of() : Map.copyOf(bubbles);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
