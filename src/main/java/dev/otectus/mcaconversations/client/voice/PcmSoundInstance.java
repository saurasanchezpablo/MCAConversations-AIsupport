package dev.otectus.mcaconversations.client.voice;

import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.entity.Entity;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * A synthesised line played from the villager's position and following them as they move, through
 * Minecraft's own sound engine (so the "Voice/Speech" volume slider, distance attenuation and pausing
 * all apply). The audio is handed straight to the engine as a stream: nothing is written to disk.
 */
final class PcmSoundInstance extends EntityBoundSoundInstance {

    /** Heard up to this many blocks away, fading with distance. */
    static final int ATTENUATION = 24;

    private final Pcm pcm;
    private final ResourceLocation id;

    PcmSoundInstance(Entity speaker, Pcm pcm, ResourceLocation id, long seed) {
        super(SoundEvent.createVariableRangeEvent(id), SoundSource.VOICE, 1.0f, 1.0f, speaker, seed);
        this.pcm = pcm;
        this.id = id;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        // A sound that exists only in memory: a streamed entry whose stream we supply ourselves.
        Sound sound = new Sound(id, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f), 1, Sound.Type.FILE, true, false,
                ATTENUATION);
        WeighedSoundEvents events = new WeighedSoundEvents(id, null);
        events.addSound(sound);
        this.sound = sound;
        return events;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary library, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new Stream(pcm));
    }

    /** Hands the PCM to the engine in the chunk sizes it asks for. */
    static final class Stream implements AudioStream {
        private final AudioFormat format;
        private final ByteBuffer data;

        Stream(Pcm pcm) {
            this.format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, pcm.sampleRate(), 16, 1, 2, pcm.sampleRate(), false);
            this.data = ByteBuffer.wrap(pcm.data());
        }

        @Override
        public AudioFormat getFormat() {
            return format;
        }

        @Override
        public ByteBuffer read(int size) {
            int remaining = data.remaining();
            if (remaining <= 0) {
                return null;
            }
            // Whole samples only: an odd byte count would shift every later sample.
            int n = Math.min(size, remaining) & ~1;
            if (n == 0) {
                return null;
            }
            ByteBuffer out = BufferUtils.createByteBuffer(n);
            ByteBuffer slice = data.slice();
            slice.limit(n);
            out.put(slice);
            out.flip();
            data.position(data.position() + n);
            return out;
        }

        @Override
        public void close() {
        }
    }
}
