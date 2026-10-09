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

/** Кой глас чете преводите. */
enum class VoiceEngine(val key: String) {
    /** Вграденият гласов синтез на телефона - по подразбиране, работи и без интернет. */
    PHONE("phone"),

    /** Гласовете на Microsoft (Edge) - по-естествени, безплатни, но искат интернет. */
    MICROSOFT("microsoft");

    companion object {
        fun fromKey(key: String?): VoiceEngine = entries.firstOrNull { it.key == key } ?: PHONE
    }
}

/**
 * Четене на глас: вграденият в Android гласов синтез (TextToSpeech) или
 * гласовете на Microsoft. Ако Microsoft не е достъпен (няма интернет, няма
 * глас за езика), чете гласът на телефона и onEvent съобщава защо.
 */
class Speaker(context: Context, http: OkHttpClient, private val onEvent: (Event) -> Unit) {

    enum class Result { STARTED, NOT_READY, MISSING_DATA, NOT_SUPPORTED, ERROR }

    sealed interface Event {
        /** Microsoft няма глас за езика - чете телефонът. */
        data object MicrosoftNoVoice : Event

        /** Гласът на Microsoft не е достъпен (обикновено няма интернет) - чете телефонът. */
        data class MicrosoftFailed(val detail: String) : Event

        /** Резултатът от резервното четене с гласа на телефона, ако не е тръгнало. */
        data class PhoneResult(val result: Result) : Event
    }

    private val appContext = context.applicationContext
    private val edge = EdgeTts(http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var edgeJob: Job? = null

    private var ready = false
    private var lastUtteranceId: String? = null

    private val _speakingText = MutableStateFlow<String?>(null)

    /** Текстът, който се чете в момента (null - нищо не се чете). */
    val speakingText: StateFlow<String?> = _speakingText.asStateFlow()

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    init {
        // Останали файлове от прекъснато четене (напр. затворено приложение).
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
     * languageTag - BCP-47 етикетът на езика ("bg", "zh-CN"), locale - същият
     * за гласа на телефона. С MICROSOFT връща STARTED веднага, а звукът идва
     * след малко (проблемите - през onEvent).
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
    // Гласът на телефона
    // ------------------------------------------------------------------

    /** shownAs - текстът за бутона "Спри" (при резервно четене на остатъка - целият текст). */
    private fun speakWithPhone(text: String, locale: Locale, rate: Float, shownAs: String = text): Result {
        if (!ready) return Result.NOT_READY
        when (tts.setLanguage(locale)) {
            TextToSpeech.LANG_MISSING_DATA -> return Result.MISSING_DATA
            TextToSpeech.LANG_NOT_SUPPORTED -> return Result.NOT_SUPPORTED
        }
        tts.setSpeechRate(rate)

        // Дълъг текст се разделя - движокът има ограничение за дължината на едно изречение за четене.
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
    // Гласовете на Microsoft
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
                // Без интернет съобщението и без това го казва - технически детайл само за други грешки.
                val offline = e is java.net.UnknownHostException || e is java.net.ConnectException ||
                    e is java.net.SocketTimeoutException
                Event.MicrosoftFailed(if (offline) "" else e.message ?: e.javaClass.simpleName)
            }
            if (fallback != null) {
                // Не спираме на грешка - остатъкът се прочита с гласа на телефона.
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
     * Частите се синтезират една след друга, а следващата се сваля, докато
     * текущата се чете - така четенето започва бързо и няма паузи.
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
                next?.cancel() // спряно или грешка - следващата част вече не трябва
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
        cont.invokeOnCancellation { release() } // спиране - от главната нишка, както и пускането
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
