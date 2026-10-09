package com.overlingo.android.tts

import com.overlingo.android.translate.HttpStatusException
import com.overlingo.android.translate.fetch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Услугата на Microsoft отказа или не върна звук. */
class EdgeTtsException(message: String, val status: Int = 0) : IOException(message)

/**
 * Гласовете на Microsoft (безплатни, без ключ, нужен е интернет) - като
 * edge-tts в десктоп версията. Всяка част от текста е отделна WebSocket
 * връзка, която връща MP3.
 */
class EdgeTts(private val http: OkHttpClient) {

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }

    private val voicesLock = Mutex()
    private var voices: List<EdgeVoice>? = null

    /** Списъкът с гласове - сваля се веднъж (около 400 гласа) и се пази в паметта. */
    suspend fun voices(): List<EdgeVoice> = voicesLock.withLock {
        voices ?: retryOnClockSkew { fetchVoices() }.also { voices = it }
    }

    suspend fun voiceFor(languageTag: String, gender: VoiceGender): EdgeVoice? =
        EdgeProtocol.pickVoice(voices(), languageTag, gender)

    /** Синтезира една част от текста (до ~1200 знака) и връща MP3. */
    suspend fun synthesize(text: String, voice: EdgeVoice, rate: Float): ByteArray = retryOnClockSkew {
        // WebSocket няма собствен timeout за отговора - без това би чакал вечно.
        withTimeoutOrNull(TIMEOUT_MS) { synthesizeOnce(text, voice, rate) } ?: throw EdgeTtsException("timeout")
    }

    private suspend fun fetchVoices(): List<EdgeVoice> {
        val request = Request.Builder()
            .url(EdgeProtocol.voiceListUrl())
            .header("User-Agent", EdgeProtocol.USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${EdgeProtocol.muid()};")
            .build()
        val json = JSONArray(http.fetch(request))
        return (0 until json.length()).mapNotNull { i ->
            val voice = json.optJSONObject(i) ?: return@mapNotNull null
            val shortName = voice.optString("ShortName")
            if (shortName.isEmpty()) null else EdgeVoice(shortName, voice.optString("Locale"), voice.optString("Gender"))
        }
    }

    private suspend fun synthesizeOnce(text: String, voice: EdgeVoice, rate: Float): ByteArray =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url(EdgeProtocol.webSocketUrl())
                .header("User-Agent", EdgeProtocol.USER_AGENT)
                .header("Origin", EdgeProtocol.ORIGIN)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=${EdgeProtocol.muid()};")
                .build()
            val audio = ByteArrayOutputStream()
            val socket = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(EdgeProtocol.configMessage())
                    webSocket.send(EdgeProtocol.ssmlMessage(text, voice.shortName, rate))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (EdgeProtocol.textMessagePath(text) == "turn.end") {
                        webSocket.close(1000, null)
                        if (!cont.isActive) return
                        if (audio.size() == 0) {
                            cont.resumeWithException(EdgeTtsException("no audio received"))
                        } else {
                            cont.resume(audio.toByteArray())
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    EdgeProtocol.audioPayload(bytes.toByteArray())?.let { audio.write(it) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!cont.isActive) return
                    val status = response?.code ?: 0
                    if (status == 403) adjustClock(response?.header("Date"))
                    cont.resumeWithException(
                        if (status != 0) EdgeTtsException("HTTP $status", status) else t as? IOException ?: IOException(t),
                    )
                }
            })
            cont.invokeOnCancellation { socket.cancel() }
        }

    /** 403 обикновено значи, че часовникът на телефона е разминат - оправяме и пробваме пак. */
    private suspend fun <T> retryOnClockSkew(block: suspend () -> T): T = try {
        block()
    } catch (e: EdgeTtsException) {
        if (e.status != 403) throw e
        block()
    } catch (e: HttpStatusException) {
        if (e.code != 403) throw e
        block()
    }

    private fun adjustClock(serverDate: String?) {
        val serverMillis = try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(serverDate ?: return)?.time ?: return
        } catch (e: Exception) {
            return
        }
        EdgeProtocol.clockSkewSeconds = (serverMillis - System.currentTimeMillis()) / 1000
    }
}
