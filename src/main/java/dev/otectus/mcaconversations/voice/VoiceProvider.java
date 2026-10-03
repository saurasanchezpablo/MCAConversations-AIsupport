package dev.otectus.mcaconversations.voice;

/** Which speech engine voices villagers on this client. */
public enum VoiceProvider {
    /** MCA's own TTS, unchanged (no acting, MCA's settings). */
    MCA,
    /** OpenAI's speech API ({@code gpt-4o-mini-tts}), directed with acting instructions. */
    OPENAI,
    /** Google Gemini TTS, directed with a style prompt. */
    GEMINI
}
