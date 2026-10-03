package dev.otectus.mcaconversations.client.voice;

/** Signed 16-bit little-endian mono PCM at {@code sampleRate}. */
record Pcm(byte[] data, int sampleRate) {

    long sizeBytes() {
        return data.length;
    }
}
