package dev.otectus.mcaconversations.network;

import dev.otectus.mcaconversations.voice.VoiceDirection;

/** Where a decoded voice direction goes on the client. Installed by client setup; a no-op elsewhere. */
@FunctionalInterface
public interface VoiceSink {

    VoiceSink NONE = direction -> {
    };

    void accept(VoiceDirection direction);
}
