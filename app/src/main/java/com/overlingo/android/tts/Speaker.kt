package com.overlingo.android.tts

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Which voice reads the translations aloud. */
enum class VoiceEngine(val key: String) {
    /** The phone's built-in speech synthesis - the default, works offline too. */
    PHONE("phone"),

    /** Microsoft (Edge) voices - more natural and free, but need internet. */
    MICROSOFT("microsoft");

    companion object {
        fun fromKey(key: String?): VoiceEngine = entries.firstOrNull { it.key == key } ?: PHONE
    }
}

/**
 * Reading aloud: Android's built-in speech synthesis (TextToSpeech) or
 * Microsoft's voices. If Microsoft is unavailable (no internet, no voice
 * for the language), the phone's voice reads instead and onEvent reports why.
 */
class Speaker(context: Context, http: OkHttpClient, private val onEvent: (Event) -> Unit) {

    enum class Result { STARTED, NOT_READY, MISSING_DATA, NOT_SUPPORTED, ERROR }

    sealed interface Event {
        /** Microsoft has no voice for the language - the phone reads instead. */
        data object MicrosoftNoVoice : Event

        /** The Microsoft voice is unavailable (usually no internet) - the phone reads instead. */
        data class MicrosoftFailed(val detail: String) : Event

        /** The result of the fallback reading with the phone's voice, if it failed to start. */
        data class PhoneResult(val result: Result) : Event
    }

    private val appContext = context.applicationContext
    private val edge = EdgeTts(http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var edgeJob: Job? = null

    private var ready = false
    private var lastUtteranceId: String? = null

    private val _speakingText = MutableStateFlow<String?>(null)

    /** The text currently being read (null - nothing is being read). */
    val speakingText: StateFlow<String?> = _speakingText.asStateFlow()

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    init {
        // Leftover files from interrupted reading (e.g. the app was closed).
        appContext.cacheDir.listFiles { f -> f.name.startsWith("voice-") && f.name.endsWith(".mp3") }
            ?.forEach { it.delete() }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastUtteranceId) _speakingText.value = null
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _speakingText.value = null
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _speakingText.value = null
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == lastUtteranceId) _speakingText.value = null
            }
        })
    }

    /**
     * languageTag - the language's BCP-47 tag ("bg", "zh-CN"), locale - the same
     * for the phone's voice. With MICROSOFT it returns STARTED immediately and the
     * audio follows shortly after (problems are reported via onEvent).
     */
    fun speak(
        text: String,
        languageTag: String,
        locale: Locale,
        rate: Float,
        engine: VoiceEngine = VoiceEngine.PHONE,
        gender: VoiceGender = VoiceGender.FEMALE,
    ): Result {
        stop()
        return when (engine) {
            VoiceEngine.PHONE -> speakWithPhone(text, locale, rate)
            VoiceEngine.MICROSOFT -> {
                speakWithMicrosoft(text, languageTag, locale, rate, gender)
                Result.STARTED
            }
        }
    }

    fun stop() {
        edgeJob?.cancel()
        edgeJob = null
        tts.stop()
        _speakingText.value = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
        tts.shutdown()
    }

    // ------------------------------------------------------------------
    // Phone voice
    // ------------------------------------------------------------------

    /** shownAs - the text for the "Stop" button (when falling back for the remainder - the full text). */
    private fun speakWithPhone(text: String, locale: Locale, rate: Float, shownAs: String = text): Result {
        if (!ready) return Result.NOT_READY
        when (tts.setLanguage(locale)) {
            TextToSpeech.LANG_MISSING_DATA -> return Result.MISSING_DATA
            TextToSpeech.LANG_NOT_SUPPORTED -> return Result.NOT_SUPPORTED
        }
        tts.setSpeechRate(rate)

        // Long text is split - the engine has a length limit for a single utterance.
        val chunks = splitText(text, TextToSpeech.getMaxSpeechInputLength() - 100)
        val batch = System.nanoTime()
        lastUtteranceId = "overlingo-$batch-${chunks.lastIndex}"
        _speakingText.value = shownAs
        var queueMode = TextToSpeech.QUEUE_FLUSH
        chunks.forEachIndexed { i, chunk ->
            if (tts.speak(chunk, queueMode, null, "overlingo-$batch-$i") != TextToSpeech.SUCCESS) {
                _speakingText.value = null
                return Result.ERROR
            }
            queueMode = TextToSpeech.QUEUE_ADD
        }
        return Result.STARTED
    }

    private fun splitText(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val chunks = mutableListOf<String>()
        var rest = text
        while (rest.length > maxLen) {
            val cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '\n', ' '), maxLen).takeIf { it > 0 } ?: maxLen
            chunks += rest.substring(0, cut + 1)
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) chunks += rest
        return chunks
    }

    // ------------------------------------------------------------------
    // Microsoft voices
    // ------------------------------------------------------------------

    private fun speakWithMicrosoft(text: String, languageTag: String, locale: Locale, rate: Float, gender: VoiceGender) {
        _speakingText.value = text
        edgeJob = scope.launch {
            val parts = EdgeProtocol.splitText(text)
            var played = 0
            val fallback: Event? = try {
                val voice = edge.voiceFor(languageTag, gender)
                if (voice == null) {
                    Event.MicrosoftNoVoice
                } else {
                    playMicrosoft(parts, voice, rate) { played++ }
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // When offline the message already says so - technical detail only for other errors.
                val offline = e is java.net.UnknownHostException || e is java.net.ConnectException ||
                    e is java.net.SocketTimeoutException
                Event.MicrosoftFailed(if (offline) "" else e.message ?: e.javaClass.simpleName)
            }
            if (fallback != null) {
                // Don't stop on error - the remainder is read with the phone's voice.
                onEvent(fallback)
                val rest = parts.drop(played).joinToString(" ")
                val result = speakWithPhone(rest, locale, rate, shownAs = text)
                if (result != Result.STARTED) {
                    _speakingText.value = null
                    onEvent(Event.PhoneResult(result))
                }
            } else if (_speakingText.value == text) {
                _speakingText.value = null
            }
        }
    }

    /**
     * Parts are synthesized one after another, and the next one downloads while
     * the current one plays - so playback starts quickly with no gaps.
     */
    private suspend fun playMicrosoft(parts: List<String>, voice: EdgeVoice, rate: Float, onPlayed: () -> Unit) =
        supervisorScope {
            var next: Deferred<File>? = async(Dispatchers.IO) { synthesizeToFile(parts[0], voice, rate, 0) }
            try {
                for (i in parts.indices) {
                    val file = next!!.await()
                    next = if (i + 1 < parts.size) {
                        async(Dispatchers.IO) { synthesizeToFile(parts[i + 1], voice, rate, i + 1) }
                    } else {
                        null
                    }
                    try {
                        play(file)
                    } finally {
                        file.delete()
                    }
                    onPlayed()
                }
            } finally {
                next?.cancel() // stopped or failed - the next part is no longer needed
            }
        }

    private suspend fun synthesizeToFile(part: String, voice: EdgeVoice, rate: Float, index: Int): File {
        val audio = edge.synthesize(part, voice, rate)
        return withContext(Dispatchers.IO) {
            File(appContext.cacheDir, "voice-$index-${System.nanoTime()}.mp3").apply { writeBytes(audio) }
        }
    }

    private suspend fun play(file: File) = suspendCancellableCoroutine { cont ->
        val player = MediaPlayer()
        var released = false
        fun release() {
            if (!released) {
                released = true
                player.release()
            }
        }
        player.setOnCompletionListener {
            release()
            if (cont.isActive) cont.resume(Unit)
        }
        player.setOnErrorListener { _, what, extra ->
            release()
            if (cont.isActive) cont.resumeWithException(EdgeTtsException("player error $what/$extra"))
            true
        }
        cont.invokeOnCancellation { release() } // stopping happens on the main thread, same as starting
        try {
            player.setDataSource(file.path)
            player.prepare()
            player.start()
        } catch (e: Exception) {
            release()
            if (cont.isActive) cont.resumeWithException(e)
        }
    }
}
