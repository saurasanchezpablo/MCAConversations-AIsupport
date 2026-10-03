package dev.otectus.mcaconversations.network;

import dev.otectus.mcaconversations.McaConversations;
import dev.otectus.mcaconversations.voice.VoiceDirection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * How a villager's next line should sound (protocol 5). Sent just before MCA delivers the line, to
 * every player close enough to hear it; the client keeps it until the line arrives and voices the line
 * with it. Carries no game state: a client that ignores it loses nothing but the acting.
 */
public record VoiceDirectionS2C(VoiceDirection direction) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<VoiceDirectionS2C> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(McaConversations.MOD_ID, "voice_direction"));
    public static final StreamCodec<FriendlyByteBuf, VoiceDirectionS2C> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> payload.direction.write(buf), buf -> new VoiceDirectionS2C(VoiceDirection.read(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
