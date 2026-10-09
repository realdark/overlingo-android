package com.overlingo.android.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** HTTP response with an error status code (4xx/5xx). */
class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code ${body.take(200)}")

/** Executes a request without blocking a thread; cancelling the coroutine also cancels the request. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { response.close() } // on cancellation the response is closed
        }
    })
}

/**
 * Returns the response body as text (read on a background thread). On transient problems (429, 5xx,
 * dropped connection) it retries up to 3 times with an increasing delay - like
 * retry_with_backoff in the desktop version.
 */
suspend fun OkHttpClient.fetch(request: Request, attempts: Int = 3): String = withContext(Dispatchers.IO) {
    var lastError: IOException? = null
    repeat(attempts) { attempt ->
        // On 429 ("too many requests") we wait longer, otherwise just a short pause.
        if (attempt > 0) delay(if ((lastError as? HttpStatusException)?.code == 429) 2000L * attempt else 800L * attempt)
        try {
            newCall(request).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw HttpStatusException(response.code, body)
                return@withContext body
            }
        } catch (e: HttpStatusException) {
            if (e.code != 429 && e.code < 500) throw e
            lastError = e
        } catch (e: UnknownHostException) {
            throw e // no internet / DNS - retrying won't help
        } catch (e: IOException) {
            lastError = e
        }
    }
    throw lastError ?: IOException("request failed")
}
