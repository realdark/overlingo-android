package com.overlingo.android.translate

import com.overlingo.android.data.AppSettings
import com.overlingo.android.data.Language
import com.overlingo.android.data.ServiceType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ParsersTest {

    @Test
    fun googleJoinsSentenceSegments() {
        val json = """[[["Здравей. ","Hello. ",null,null,10],["Свят.","World.",null,null,10]],null,"en"]"""
        assertEquals("Здравей. Свят.", GoogleTranslator.parseResponse(json))
    }

    @Test
    fun googleKeepsLineBreaksBetweenBlocks() {
        val json = """[[["Изход\n","Exit\n",null,null,10],["Вход","Entrance",null,null,10]],null,"en"]"""
        assertEquals(listOf("Изход", "Вход"), GoogleTranslator.parseResponse(json).split('\n'))
    }

    @Test
    fun googleEmptyResponse() {
        assertEquals("", GoogleTranslator.parseResponse("""[null,null,"en"]"""))
    }

    @Test
    fun chunksRespectLimitAndOrder() {
        val texts = listOf("a".repeat(10), "b".repeat(10), "c".repeat(10), "d".repeat(50))
        val chunks = GoogleTranslator.chunkIndices(texts, 25)
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3)), chunks)
        assertEquals(texts.indices.toList(), chunks.flatten())
    }

    @Test
    fun deeplResponse() {
        val json = """{"translations":[{"detected_source_language":"EN","text":"Здравей"},{"text":"Свят"}]}"""
        assertEquals(listOf("Здравей", "Свят"), DeepLTranslator.parseResponse(json))
    }

    @Test
    fun microsoftResponse() {
        val json = """[{"translations":[{"text":"Здравей","to":"bg"}]},{"translations":[{"text":"Свят","to":"bg"}]}]"""
        assertEquals(listOf("Здравей", "Свят"), MicrosoftTranslator.parseResponse(json))
    }

    @Test
    fun httpErrorsAreMapped() {
        assertEquals(TranslationException.Kind.INVALID_KEY, mapError(HttpStatusException(403, ""), "DeepL").kind)
        assertEquals(TranslationException.Kind.RATE_LIMIT, mapError(HttpStatusException(456, ""), "DeepL").kind)
        assertEquals(TranslationException.Kind.NO_INTERNET, mapError(java.net.UnknownHostException("x"), "Google").kind)
        assertEquals(TranslationException.Kind.OTHER, mapError(HttpStatusException(500, ""), "Google").kind)
    }
}

class CacheTest {

    @Test
    fun whitespaceAndCaseAreIgnored() {
        val cache = TranslationCache()
        cache.put("Hello   World", "Здравей, свят", "BG")
        assertEquals("Здравей, свят", cache.get("  hello\nworld ", "bg"))
        assertNull(cache.get("Hello World", "DE"))
    }

    @Test
    fun oldestUnusedEntryIsDropped() {
        val cache = TranslationCache(maxItems = 2)
        cache.put("a", "1", "BG")
        cache.put("b", "2", "BG")
        cache.get("a", "BG")
        cache.put("c", "3", "BG")
        assertEquals("1", cache.get("a", "BG"))
        assertNull(cache.get("b", "BG"))
        assertEquals(2, cache.size())
    }
}

/** Test translator: records what was requested and returns "[code] text" or throws an error. */
private class FakeTranslator(private val error: Exception? = null) : Translator {
    val calls = mutableListOf<List<String>>()
    override suspend fun translate(texts: List<String>, target: Language): List<String> {
        calls += texts
        error?.let { throw it }
        return texts.map { "[${target.code}] $it" }
    }
}

class EngineTest {

    private val http = OkHttpClient()

    private fun engine(
        primary: Translator,
        fallback: Translator = FakeTranslator(),
        offline: Translator? = null,
    ) = TranslationEngine(
        http,
        factory = { _, _ -> primary },
        fallbackFactory = { fallback },
        offlineFallback = offline,
    )

    @Test
    fun translatesAllBlocksInOneCall() = runBlocking {
        val fake = FakeTranslator()
        val outcome = engine(fake).translate(listOf("Exit", "Entrance"), AppSettings(targetLang = "BG"))
        assertEquals(listOf("[BG] Exit", "[BG] Entrance"), outcome.translations)
        assertEquals(1, fake.calls.size)
        assertFalse(outcome.usedFallback)
    }

    @Test
    fun cachedBlocksAreNotSentAgain() = runBlocking {
        val fake = FakeTranslator()
        val e = engine(fake)
        val settings = AppSettings(targetLang = "BG")
        e.translate(listOf("Exit"), settings)
        val outcome = e.translate(listOf("Exit", "Push"), settings)
        assertEquals(listOf("[BG] Exit", "[BG] Push"), outcome.translations)
        assertEquals(listOf(listOf("Exit"), listOf("Push")), fake.calls)
    }

    @Test
    fun changingServiceDoesNotReuseCache() = runBlocking {
        val fake = FakeTranslator()
        val e = engine(fake)
        e.translate(listOf("Exit"), AppSettings(targetLang = "BG"))
        e.translate(listOf("Exit"), AppSettings(targetLang = "BG", service = ServiceType.DEEPL, deeplKey = "k"))
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun noInternetUsesOfflineModels() = runBlocking {
        val offline = FakeTranslator()
        val noInternet = FakeTranslator(TranslationException(TranslationException.Kind.NO_INTERNET))
        val outcome = engine(noInternet, offline = offline).translate(listOf("Exit"), AppSettings(targetLang = "BG"))
        assertTrue(outcome.usedOffline)
        assertEquals(listOf("[BG] Exit"), outcome.translations)
    }

    @Test
    fun noInternetWithoutModelsStillReportsNoInternet() = runBlocking {
        val missing = FakeTranslator(TranslationException(TranslationException.Kind.OFFLINE_MODEL_MISSING))
        val noInternet = FakeTranslator(TranslationException(TranslationException.Kind.NO_INTERNET))
        try {
            engine(noInternet, offline = missing).translate(listOf("Exit"), AppSettings())
            fail("expected an error")
        } catch (e: TranslationException) {
            assertEquals(TranslationException.Kind.NO_INTERNET, e.kind)
        }
    }

    @Test
    fun offlineResultsAreNotCached() = runBlocking {
        val primary = FakeTranslator(TranslationException(TranslationException.Kind.NO_INTERNET))
        val e = engine(primary, offline = FakeTranslator())
        e.translate(listOf("Exit"), AppSettings())
        e.translate(listOf("Exit"), AppSettings())
        assertEquals(2, primary.calls.size) // the second attempt tries the online service again
    }

    @Test
    fun paidServiceFallsBackToGoogle() = runBlocking {
        val failing = FakeTranslator(TranslationException(TranslationException.Kind.OTHER, "down"))
        val google = FakeTranslator()
        val settings = AppSettings(targetLang = "DE", service = ServiceType.DEEPL, deeplKey = "k")
        val outcome = engine(failing, google).translate(listOf("Hallo"), settings)
        assertTrue(outcome.usedFallback)
        assertEquals(listOf("[DE] Hallo"), outcome.translations)
    }

    @Test
    fun noFallbackWhenDisabledOrKeyMissing() = runBlocking {
        val google = FakeTranslator()
        for (settings in listOf(
            AppSettings(service = ServiceType.DEEPL, deeplKey = "k", fallback = false),
            AppSettings(service = ServiceType.MICROSOFT),
        )) {
            val kind = if (settings.fallback) TranslationException.Kind.MISSING_KEY else TranslationException.Kind.OTHER
            try {
                engine(FakeTranslator(TranslationException(kind)), google).translate(listOf("x"), settings)
                fail("expected an error")
            } catch (e: TranslationException) {
                assertEquals(kind, e.kind)
            }
        }
        assertTrue(google.calls.isEmpty())
    }

    @Test
    fun missingKeyIsReportedBeforeAnyRequest() = runBlocking {
        try {
            DeepLTranslator(http, " ").translate(listOf("x"), AppSettings().target)
            fail("expected an error")
        } catch (e: TranslationException) {
            assertEquals(TranslationException.Kind.MISSING_KEY, e.kind)
        }
    }
}

/** Google without a network: answers by a given rule and counts the requests. */
private class FakeGoogle(private val answer: (String) -> String) : GoogleTranslator(OkHttpClient()) {
    val requests = mutableListOf<String>()
    override suspend fun translateOne(text: String, target: Language): String {
        requests += text
        return answer(text)
    }
}

class GoogleBatchTest {

    private val target = AppSettings(targetLang = "BG").target

    @Test
    fun allBlocksGoInOneRequest() = runBlocking {
        val google = FakeGoogle { it.uppercase() }
        assertEquals(listOf("A", "B", "C"), google.translate(listOf("a", "b", "c"), target))
        assertEquals(1, google.requests.size)
    }

    @Test
    fun trailingNewlineIsIgnored() = runBlocking {
        val google = FakeGoogle { it.uppercase() + "\n" }
        assertEquals(listOf("A", "B"), google.translate(listOf("a", "b"), target))
        assertEquals(1, google.requests.size)
    }

    @Test
    fun mismatchSplitsInHalvesInsteadOfOneRequestPerBlock() = runBlocking {
        // Google merges the lines whenever the request has more than 2 blocks.
        val google = FakeGoogle { text ->
            val lines = text.split('\n')
            if (lines.size > 2) lines.joinToString(" ") else text.uppercase()
        }
        val texts = (1..8).map { "t$it" }
        assertEquals(texts.map { it.uppercase() }, google.translate(texts, target))
        // 1 (all) + 2 (4 each) + 4 (2 each) = 7 requests, instead of 8 separate ones after the mismatch.
        assertEquals(7, google.requests.size)
    }

    @Test
    fun newlinesInsideBlockDoNotBreakBatching() = runBlocking {
        val google = FakeGoogle { it.uppercase() }
        assertEquals(listOf("A B", "C"), google.translate(listOf("a\nb", "c"), target))
        assertEquals(1, google.requests.size)
    }
}
