package com.overlingo.android.tts

import java.math.BigInteger
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Глас на Microsoft, напр. ShortName "bg-BG-KalinaNeural", Locale "bg-BG", Gender "Female". */
data class EdgeVoice(val shortName: String, val locale: String, val gender: String)

enum class VoiceGender(val key: String, val edgeName: String) {
    FEMALE("female", "Female"), MALE("male", "Male");

    companion object {
        fun fromKey(key: String?): VoiceGender = entries.firstOrNull { it.key == key } ?: FEMALE
    }
}

/**
 * Протоколът на безплатните гласове на Microsoft (същите, които ползва
 * "Четене на глас" в Edge и библиотеката edge-tts в десктоп версията).
 * Тук е само чистата логика - без мрежа, за да се тества лесно.
 * Константите следват edge-tts (github.com/rany2/edge-tts, constants.py / drm.py).
 */
object EdgeProtocol {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val BASE = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private val CHROMIUM_MAJOR = CHROMIUM_FULL_VERSION.substringBefore('.')
    const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
    const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

    val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"
    const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    /** Разликата между часовника на телефона и сървъра (оправя се при 403). */
    @Volatile
    var clockSkewSeconds: Long = 0

    private const val WIN_EPOCH = 11_644_473_600L

    fun webSocketUrl(nowUnixSeconds: Long = nowSeconds()): String =
        "wss://$BASE/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&ConnectionId=${connectId()}&Sec-MS-GEC=${secMsGec(nowUnixSeconds)}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

    fun voiceListUrl(nowUnixSeconds: Long = nowSeconds()): String =
        "https://$BASE/voices/list?trustedclienttoken=$TRUSTED_CLIENT_TOKEN" +
            "&Sec-MS-GEC=${secMsGec(nowUnixSeconds)}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

    fun nowSeconds(): Long = System.currentTimeMillis() / 1000 + clockSkewSeconds

    /**
     * Токенът Sec-MS-GEC: времето във формат на Windows (100 ns от 1601 г.),
     * закръглено надолу до 5 минути, + TRUSTED_CLIENT_TOKEN -> SHA-256 (главни букви).
     */
    fun secMsGec(unixSeconds: Long): String {
        var seconds = unixSeconds + WIN_EPOCH
        seconds -= seconds % 300
        val ticks = seconds * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256").digest("$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /** Случаен идентификатор за бисквитката "muid" (32 шестнадесетични знака). */
    fun muid(): String = BigInteger(1, ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })
        .toString(16).padStart(32, '0').uppercase(Locale.US)

    fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

    /** Дата като в JavaScript - сървърът я иска точно в този вид. */
    fun dateString(epochMillis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    /** "bg-BG-KalinaNeural" -> "Microsoft Server Speech Text to Speech Voice (bg-BG, KalinaNeural)". */
    fun fullVoiceName(shortName: String): String {
        val match = Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").find(shortName) ?: return shortName
        var (lang, region, name) = match.destructured
        val dash = name.indexOf('-')
        if (dash != -1) {
            region = "$region-${name.substring(0, dash)}"
            name = name.substring(dash + 1)
        }
        return "Microsoft Server Speech Text to Speech Voice ($lang-$region, $name)"
    }

    /** Скорост 1.0 -> "+0%", 1.5 -> "+50%", 0.75 -> "-25%". */
    fun rateString(rate: Float): String {
        val percent = Math.round((rate - 1f) * 100f)
        return if (percent >= 0) "+$percent%" else "$percent%"
    }

    fun configMessage(date: String = dateString()): String =
        "X-Timestamp:$date\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}\r\n"

    fun ssmlMessage(text: String, voiceShortName: String, rate: Float, date: String = dateString()): String =
        "X-RequestId:${connectId()}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${date}Z\r\n" + // "Z" нарочно - така прави и Edge
            "Path:ssml\r\n\r\n" +
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='${fullVoiceName(voiceShortName)}'>" +
            "<prosody pitch='+0Hz' rate='${rateString(rate)}' volume='+0%'>" +
            escapeXml(cleanText(text)) +
            "</prosody></voice></speak>"

    /** Знаци, които услугата не приема (напр. вертикална табулация от OCR) -> интервал. */
    fun cleanText(text: String): String = buildString(text.length) {
        for (c in text) {
            val code = c.code
            append(if (code in 0..8 || code in 11..12 || code in 14..31) ' ' else c)
        }
    }

    fun escapeXml(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    /** "Path" от заглавната част на текстово съобщение ("Path:turn.end" -> "turn.end"). */
    fun textMessagePath(message: String): String? = headerValue(message.substringBefore("\r\n\r\n"), "Path")

    /**
     * Двоично съобщение: първите 2 байта са дължината на заглавната част,
     * следват заглавията и после звукът (MP3). Връща звука, ако Path е audio.
     */
    fun audioPayload(data: ByteArray): ByteArray? {
        if (data.size < 2) return null
        val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        if (2 + headerLength > data.size) return null
        val headers = String(data, 2, headerLength, Charsets.UTF_8)
        if (headerValue(headers, "Path") != "audio") return null
        return data.copyOfRange(2 + headerLength, data.size)
    }

    private fun headerValue(headers: String, name: String): String? =
        headers.split("\r\n").firstNotNullOfOrNull { line ->
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).equals(name, ignoreCase = true)) line.substring(colon + 1).trim() else null
        }

    /**
     * Дълъг текст -> части до maxChars знака, рязани след изречение, нов ред
     * или интервал. Първата част е по-къса, за да започне четенето по-бързо.
     */
    fun splitText(text: String, maxChars: Int = 1200, firstMaxChars: Int = 300): List<String> {
        val parts = mutableListOf<String>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            val limit = if (parts.isEmpty()) firstMaxChars else maxChars
            if (rest.length <= limit) {
                parts += rest
                break
            }
            val window = rest.substring(0, limit)
            val sentenceEnd = Regex("[.!?…。！？][\"'”’)\\]]*\\s").findAll(window).lastOrNull()?.range?.last
            val cut = sentenceEnd?.takeIf { it > limit / 3 }
                ?: window.lastIndexOf('\n').takeIf { it > limit / 3 }
                ?: window.lastIndexOf(' ').takeIf { it > 0 }
                ?: (limit - 1)
            parts += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        return parts.filter { it.isNotEmpty() }
    }

    /** Предпочитаният регион за езици, при които не е просто "език-ЕЗИК" (bg-BG, de-DE...). */
    private val PREFERRED_REGION = mapOf(
        "en" to "US", "uk" to "UA", "el" to "GR", "cs" to "CZ", "sl" to "SI", "et" to "EE",
        "sr" to "RS", "sv" to "SE", "da" to "DK", "nb" to "NO", "he" to "IL", "hi" to "IN",
        "ja" to "JP", "ko" to "KR", "ar" to "SA", "zh" to "CN",
    )

    /**
     * Гласът за даден език (BCP-47 етикет на Overlingo: "bg", "zh-CN", "nb")
     * и предпочитан пол. Първо основният регион на езика, после всеки друг;
     * ако няма глас от избрания пол - другият. null - Microsoft няма глас за езика.
     */
    fun pickVoice(voices: List<EdgeVoice>, languageTag: String, gender: VoiceGender): EdgeVoice? {
        val language = languageTag.substringBefore('-').lowercase(Locale.US)
        val region = languageTag.substringAfter('-', "").uppercase(Locale.US).ifEmpty {
            PREFERRED_REGION[language] ?: language.uppercase(Locale.US)
        }
        val candidates = voices.filter { it.locale.substringBefore('-').equals(language, ignoreCase = true) }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareBy<EdgeVoice> { !it.locale.equals("$language-$region", ignoreCase = true) }
                .thenBy { !it.gender.equals(gender.edgeName, ignoreCase = true) }
                .thenBy { it.shortName !in PREFERRED_VOICES }
                .thenBy { it.shortName.contains("Multilingual") }
                .thenBy { it.shortName },
        ).first()
    }

    /**
     * Където в един регион има много гласове - класическите, ясни гласове
     * (иначе по азбучен ред би излязъл напр. детският en-US-AnaNeural).
     */
    private val PREFERRED_VOICES = setOf(
        "en-US-AriaNeural", "en-US-GuyNeural", "de-DE-KatjaNeural", "de-DE-ConradNeural",
        "fr-FR-DeniseNeural", "fr-FR-HenriNeural", "es-ES-ElviraNeural", "es-ES-AlvaroNeural",
        "it-IT-ElsaNeural", "it-IT-DiegoNeural", "zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural",
        "ja-JP-NanamiNeural", "ja-JP-KeitaNeural", "ko-KR-SunHiNeural", "ko-KR-InJoonNeural",
        "pt-PT-RaquelNeural", "pt-PT-DuarteNeural", "hi-IN-SwaraNeural", "hi-IN-MadhurNeural",
    )
}
