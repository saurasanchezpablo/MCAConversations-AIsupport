package dev.otectus.mcaconversations.network;

import dev.otectus.mcaconversations.McaConversations;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * Who this player is in an AI conversation with, so the client can show it (protocol 5). Sent when a
 * conversation starts or is refreshed, and with an empty villager when it ends.
 *
 * @param villager    the villager, or empty when the conversation is over
 * @param name        the villager's name, for display
 * @param idleMillis  how long until the conversation lapses without another line
 */
public record ConversationPartnerS2C(Optional<UUID> villager, String name, long idleMillis) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ConversationPartnerS2C> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(McaConversations.MOD_ID, "conversation_partner"));
    public static final StreamCodec<FriendlyByteBuf, ConversationPartnerS2C> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBoolean(p.villager.isPresent());
                p.villager.ifPresent(buf::writeUUID);
                buf.writeUtf(p.name, 128);
                buf.writeVarLong(p.idleMillis);
            },
            buf -> {
                Optional<UUID> villager = buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();
                return new ConversationPartnerS2C(villager, buf.readUtf(128), buf.readVarLong());
            });

    public static ConversationPartnerS2C ended() {
        return new ConversationPartnerS2C(Optional.empty(), "", 0);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
