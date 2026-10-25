package com.overlingo.android.tts

import com.overlingo.android.translate.HttpStatusException
import com.overlingo.android.translate.fetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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

/** The Microsoft service refused or returned no audio. */
class EdgeTtsException(message: String, val status: Int = 0) : IOException(message)

/**
 * Microsoft's voices (free, no key, internet required) - like
 * edge-tts in the desktop version. Each part of the text is a separate
 * WebSocket connection that returns MP3.
 */
class EdgeTts(private val http: OkHttpClient) {

    private companion object {
        /** One attempt; with the retries the phone voice takes over after ~45 s at most. */
        const val TIMEOUT_MS = 15_000L
        /** Waits before the 2nd and 3rd attempt. */
        val RETRY_DELAYS_MS = listOf(700L, 1_500L)
    }

    private val voicesLock = Mutex()
    private var voices: List<EdgeVoice>? = null

    /** The voice list - downloaded once (about 400 voices) and kept in memory. */
    suspend fun voices(): List<EdgeVoice> = voicesLock.withLock {
        voices ?: retryTransient { retryOnClockSkew { fetchVoices() } }.also { voices = it }
    }

    suspend fun voiceFor(languageTag: String, gender: VoiceGender): EdgeVoice? =
        EdgeProtocol.pickVoice(voices(), languageTag, gender)

    /** Synthesizes one part of the text (up to ~1200 characters) and returns MP3. */
    suspend fun synthesize(text: String, voice: EdgeVoice, rate: Float): ByteArray = retryTransient {
        retryOnClockSkew {
            // WebSocket has no response timeout of its own - without this it would wait forever.
            withTimeoutOrNull(TIMEOUT_MS) { synthesizeOnce(text, voice, rate) } ?: throw EdgeTtsException("timeout")
        }
    }

    /**
     * Mobile data often drops a single connection or answers slowly for a moment -
     * that used to switch straight to the phone voice ("needs internet"). Temporary
     * errors are retried twice; a real refusal (4xx) is not.
     */
    private suspend fun <T> retryTransient(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= RETRY_DELAYS_MS.size || !isTransient(e)) throw e
                delay(RETRY_DELAYS_MS[attempt++])
            }
        }
    }

    private fun isTransient(e: IOException): Boolean {
        val status = (e as? EdgeTtsException)?.status ?: (e as? HttpStatusException)?.code ?: 0
        return status == 0 || status == 429 || status >= 500
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

                // The server closed the connection before "turn.end" - without this we
                // only noticed it when the timeout ran out.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    if (cont.isActive) cont.resumeWithException(EdgeTtsException("closed by the server ($code)"))
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

    /** A 403 usually means the phone's clock is off - we correct it and retry. */
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
