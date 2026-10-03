package dev.otectus.mcaconversations.client.voice;

import dev.otectus.mcaconversations.voice.VoiceDirection;

import java.util.concurrent.CompletableFuture;

/** One text-to-speech backend. Never blocks the caller; failures complete exceptionally. */
interface SpeechEngine {

    CompletableFuture<Pcm> speak(String text, String voice, VoiceDirection direction);
}
