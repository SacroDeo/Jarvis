package dk.foss.jarvis.voice

/** Speech-to-text source. Implementations: on-device Android, or ElevenLabs Scribe. */
interface VoiceRecognizer {
    fun isAvailable(): Boolean
    fun prewarm() {}
    fun start(languageTag: String?, listener: Listener)
    fun stop()
    fun release()

    interface Listener {
        fun onReady() {}
        /**
         * The user has started speaking (speech onset detected), before any text
         * exists.  Backends with live partials can also use [onPartial]; this exists
         * for backends that only know *that* speech began and not what was said —
         * e.g. [ScribeRecognizer], which records then transcribes and therefore has
         * no partials.  Default no-op so existing listeners are unaffected.
         */
        fun onSpeechStart() {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        /** [transient] = a hiccup worth retrying (not "you said nothing"). */
        fun onError(message: String, transient: Boolean) {}
        fun onEnd() {}
    }
}
