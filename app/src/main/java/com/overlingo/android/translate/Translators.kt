package com.overlingo.android.translate

import com.overlingo.android.data.Language
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** An already classified translation error, so we can show a clear message in the UI language. */
class TranslationException(val kind: Kind, detail: String = "", cause: Throwable? = null) :
    Exception(detail, cause) {
    enum class Kind {
        NO_INTERNET, MISSING_KEY, INVALID_KEY, RATE_LIMIT,
        OFFLINE_UNSUPPORTED, OFFLINE_MODEL_MISSING, OFFLINE_DOWNLOAD_FAILED, OTHER,
    }
}

/** Every translator translates a list of texts at once (the blocks from the photo). */
interface Translator {
    suspend fun translate(texts: List<String>, target: Language): List<String>
}

private val JSON = "application/json; charset=utf-8".toMediaType()

/** Converts a network/HTTP error into a TranslationException. */
internal fun mapError(e: Exception, service: String): TranslationException = when (e) {
    is TranslationException -> e
    // No network, or Wi-Fi without internet (the connection can't be made / doesn't respond).
    is UnknownHostException, is ConnectException, is SocketTimeoutException ->
        TranslationException(TranslationException.Kind.NO_INTERNET, service, e)
    is HttpStatusException -> when (e.code) {
        401, 403 -> TranslationException(TranslationException.Kind.INVALID_KEY, service, e)
        429, 456 -> TranslationException(TranslationException.Kind.RATE_LIMIT, service, e)
        else -> TranslationException(TranslationException.Kind.OTHER, "$service: HTTP ${e.code}", e)
    }
    else -> TranslationException(TranslationException.Kind.OTHER, "$service: ${e.message}", e)
}

/**
 * Google Translate (the free web endpoint, same as in the desktop version).
 * Blocks are sent together, one per line, in a single request; if Google
 * returns a different number of lines, we translate them separately.
 */
open class GoogleTranslator(private val http: OkHttpClient) : Translator {

    override suspend fun translate(texts: List<String>, target: Language): List<String> = try {
        val result = MutableList(texts.size) { "" }
        for (chunk in chunkIndices(texts, MAX_CHUNK_CHARS)) {
            translateGroup(texts, chunk, target, result)
        }
        result
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        throw mapError(e, "Google")
    }

    /**
     * Sends a group of blocks in one request (one per line). If Google returns
     * a different number of lines, the group is split in two and retried - this
     * keeps the request count low (otherwise, with many blocks, Google returns 429).
     */
    private suspend fun translateGroup(texts: List<String>, group: List<Int>, target: Language, result: MutableList<String>) {
        if (group.size == 1) {
            result[group[0]] = translateOne(flatten(texts[group[0]]), target).trim()
            return
        }
        val parts = translateOne(group.joinToString("\n") { flatten(texts[it]) }, target).trimEnd('\n').split('\n')
        if (parts.size == group.size) {
            group.forEachIndexed { i, index -> result[index] = parts[i].trim() }
        } else {
            val half = group.size / 2
            translateGroup(texts, group.subList(0, half), target, result)
            translateGroup(texts, group.subList(half, group.size), target, result)
        }
    }

    /** A single request to Google (open - for the tests). */
    internal open suspend fun translateOne(text: String, target: Language): String {
        val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", target.google)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            .header("Referer", "https://translate.google.com/")
            .build()
        return parseResponse(http.fetch(request))
    }

    companion object {
        const val MAX_CHUNK_CHARS = 1500

        private fun flatten(text: String) = text.replace('\n', ' ').trim()

        /** The response is [[["translation","original",...], ...], ...] - we join the sentence translations. */
        fun parseResponse(json: String): String {
            val segments = JSONArray(json).optJSONArray(0) ?: return ""
            val sb = StringBuilder()
            for (i in 0 until segments.length()) {
                val segment = segments.optJSONArray(i) ?: continue
                if (!segment.isNull(0)) sb.append(segment.getString(0))
            }
            return sb.toString()
        }

        /** Groups consecutive indices so the total length doesn't exceed maxChars (a long text stays on its own). */
        fun chunkIndices(texts: List<String>, maxChars: Int): List<List<Int>> {
            val chunks = mutableListOf<MutableList<Int>>()
            var length = 0
            texts.forEachIndexed { index, text ->
                val current = chunks.lastOrNull()
                if (current == null || length + text.length + 1 > maxChars) {
                    chunks += mutableListOf(index)
                    length = text.length
                } else {
                    current += index
                    length += text.length + 1
                }
            }
            return chunks
        }
    }
}

/** DeepL REST API - accepts up to 50 texts per request. A key ending in ":fx" is the free plan. */
class DeepLTranslator(private val http: OkHttpClient, private val apiKey: String) : Translator {

    override suspend fun translate(texts: List<String>, target: Language): List<String> {
        if (apiKey.isBlank()) throw TranslationException(TranslationException.Kind.MISSING_KEY, "DeepL")
        val key = apiKey.trim()
        val host = if (key.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        return try {
            texts.chunked(50).flatMap { chunk ->
                val body = JSONObject()
                    .put("text", JSONArray(chunk))
                    .put("target_lang", target.deepl)
                    .toString()
                val request = Request.Builder().url("$host/v2/translate")
                    .header("Authorization", "DeepL-Auth-Key $key")
                    .post(body.toRequestBody(JSON))
                    .build()
                parseResponse(http.fetch(request))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw mapError(e, "DeepL")
        }
    }

    companion object {
        fun parseResponse(json: String): List<String> {
            val items = JSONObject(json).getJSONArray("translations")
            return List(items.length()) { items.getJSONObject(it).getString("text") }
        }
    }
}

/** Microsoft Translator - up to 100 texts per request. The region is only needed for regional Azure resources. */
class MicrosoftTranslator(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val region: String,
) : Translator {

    override suspend fun translate(texts: List<String>, target: Language): List<String> {
        if (apiKey.isBlank()) throw TranslationException(TranslationException.Kind.MISSING_KEY, "Microsoft")
        return try {
            texts.chunked(100).flatMap { chunk ->
                val body = JSONArray().apply { chunk.forEach { put(JSONObject().put("Text", it)) } }.toString()
                val url = "https://api.cognitive.microsofttranslator.com/translate".toHttpUrl().newBuilder()
                    .addQueryParameter("api-version", "3.0")
                    .addQueryParameter("to", target.microsoft)
                    .build()
                val request = Request.Builder().url(url)
                    .header("Ocp-Apim-Subscription-Key", apiKey.trim())
                    .apply { if (region.isNotBlank()) header("Ocp-Apim-Subscription-Region", region.trim()) }
                    .post(body.toRequestBody(JSON))
                    .build()
                parseResponse(http.fetch(request))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw mapError(e, "Microsoft")
        }
    }

    companion object {
        fun parseResponse(json: String): List<String> {
            val items = JSONArray(json)
            return List(items.length()) {
                items.getJSONObject(it).getJSONArray("translations").getJSONObject(0).getString("text")
            }
        }
    }
}
