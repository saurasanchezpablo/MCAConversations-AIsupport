package dev.otectus.mcaconversations.network;

import dev.otectus.mcaconversations.McaConversations;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Opens the player's chat box: they right-clicked a villager to talk (protocol 5). Carries nothing. */
public record OpenChatS2C() implements CustomPacketPayload {

    public static final OpenChatS2C INSTANCE = new OpenChatS2C();
    public static final CustomPacketPayload.Type<OpenChatS2C> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(McaConversations.MOD_ID, "open_chat"));
    public static final StreamCodec<FriendlyByteBuf, OpenChatS2C> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
