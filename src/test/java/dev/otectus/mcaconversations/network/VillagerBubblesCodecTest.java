package dev.otectus.mcaconversations.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VillagerBubblesCodecTest {

    @Test
    void bubblesRoundTripAndAreBounded() {
        Map<UUID, Byte> bubbles = Map.of(UUID.randomUUID(), VillagerBubblesS2C.URGENT, UUID.randomUUID(),
                VillagerBubblesS2C.EVENT, UUID.randomUUID(), VillagerBubblesS2C.NEWS);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        VillagerBubblesS2C.STREAM_CODEC.encode(buffer, new VillagerBubblesS2C(bubbles));
        assertEquals(bubbles, VillagerBubblesS2C.STREAM_CODEC.decode(buffer).bubbles());
        assertEquals(0, buffer.readableBytes());

        Map<UUID, Byte> many = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            many.put(UUID.randomUUID(), VillagerBubblesS2C.NEWS);
        }
        FriendlyByteBuf big = new FriendlyByteBuf(Unpooled.buffer());
        VillagerBubblesS2C.STREAM_CODEC.encode(big, new VillagerBubblesS2C(many));
        assertEquals(VillagerBubblesS2C.MAX, VillagerBubblesS2C.STREAM_CODEC.decode(big).bubbles().size());
    }
}
