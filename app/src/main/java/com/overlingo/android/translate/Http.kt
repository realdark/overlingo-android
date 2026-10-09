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

/** HTTP отговор с код за грешка (4xx/5xx). */
class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code ${body.take(200)}")

/** Изпълнява заявка без да блокира нишка; отказът на корутината прекъсва и заявката. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { response.close() } // при отказ отговорът се затваря
        }
    })
}

/**
 * Връща тялото на отговора като текст (чете го във фонова нишка). При временни проблеми (429, 5xx,
 * прекъсната връзка) опитва пак до 3 пъти с нарастваща пауза - като
 * retry_with_backoff в десктоп версията.
 */
suspend fun OkHttpClient.fetch(request: Request, attempts: Int = 3): String = withContext(Dispatchers.IO) {
    var lastError: IOException? = null
    repeat(attempts) { attempt ->
        // При 429 ("твърде много заявки") чакаме по-дълго, иначе - кратка пауза.
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
            throw e // няма интернет / DNS - нов опит няма да помогне
        } catch (e: IOException) {
            lastError = e
        }
    }
    throw lastError ?: IOException("request failed")
}
