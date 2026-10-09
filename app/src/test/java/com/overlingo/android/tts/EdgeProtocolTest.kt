package com.overlingo.android.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeProtocolTest {

    @Test
    fun secMsGecMatchesEdgeTts() {
        // Стойността е изчислена със същата формула като drm.py в edge-tts.
        assertEquals(
            "A70BE98FDEC154E8EAB9A86F9653186A19C081EB633F37CEEDCB7B25F6DD2575",
            EdgeProtocol.secMsGec(1_759_590_000L),
        )
        // В рамките на 5 минути токенът е един и същ.
        assertEquals(EdgeProtocol.secMsGec(1_759_590_000L), EdgeProtocol.secMsGec(1_759_590_299L))
    }

    @Test
    fun fullVoiceName() {
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (bg-BG, KalinaNeural)",
            EdgeProtocol.fullVoiceName("bg-BG-KalinaNeural"),
        )
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (zh-CN-liaoning, XiaobeiNeural)",
            EdgeProtocol.fullVoiceName("zh-CN-liaoning-XiaobeiNeural"),
        )
    }

    @Test
    fun rateString() {
        assertEquals("+0%", EdgeProtocol.rateString(1f))
        assertEquals("+50%", EdgeProtocol.rateString(1.5f))
        assertEquals("-25%", EdgeProtocol.rateString(0.75f))
    }

    @Test
    fun dateStringIsJavascriptStyle() {
        assertEquals(
            "Thu Jan 01 1970 00:00:00 GMT+0000 (Coordinated Universal Time)",
            EdgeProtocol.dateString(0),
        )
    }

    @Test
    fun ssmlEscapesTextAndUsesFullVoiceName() {
        val message = EdgeProtocol.ssmlMessage("A & B <c>\u000B", "en-US-AriaNeural", 1.25f, date = "D")
        assertTrue(message.contains("X-Timestamp:DZ\r\nPath:ssml\r\n\r\n"))
        assertTrue(message.contains("<voice name='Microsoft Server Speech Text to Speech Voice (en-US, AriaNeural)'>"))
        assertTrue(message.contains("rate='+25%'"))
        assertTrue(message.contains("A &amp; B &lt;c&gt; </prosody>"))
    }

    @Test
    fun audioPayloadParsesBinaryMessage() {
        val headers = "X-RequestId:1\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n".toByteArray()
        val audio = byteArrayOf(1, 2, 3)
        val message = byteArrayOf(0, headers.size.toByte()) + headers + audio
        assertTrue(audio.contentEquals(EdgeProtocol.audioPayload(message)))

        val other = "Path:turn.start\r\n".toByteArray()
        assertNull(EdgeProtocol.audioPayload(byteArrayOf(0, other.size.toByte()) + other + audio))
        assertNull(EdgeProtocol.audioPayload(byteArrayOf(0, 50, 1)))
    }

    @Test
    fun textMessagePath() {
        assertEquals("turn.end", EdgeProtocol.textMessagePath("X-RequestId:1\r\nPath:turn.end\r\n\r\n{}"))
    }

    @Test
    fun splitTextKeepsEverythingAndRespectsLimits() {
        val text = (1..80).joinToString(" ") { "Sentence number $it is here." }
        val parts = EdgeProtocol.splitText(text, maxChars = 200, firstMaxChars = 60)
        assertTrue(parts.first().length <= 60)
        assertTrue(parts.all { it.length <= 200 })
        assertTrue(parts.first().endsWith("."))
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
        assertEquals(listOf("Short."), EdgeProtocol.splitText("  Short.  "))
    }

    private val voices = listOf(
        EdgeVoice("bg-BG-BorislavNeural", "bg-BG", "Male"),
        EdgeVoice("bg-BG-KalinaNeural", "bg-BG", "Female"),
        EdgeVoice("en-GB-SoniaNeural", "en-GB", "Female"),
        EdgeVoice("en-US-AnaNeural", "en-US", "Female"),
        EdgeVoice("en-US-AriaNeural", "en-US", "Female"),
        EdgeVoice("en-US-GuyNeural", "en-US", "Male"),
        EdgeVoice("zh-CN-XiaoxiaoNeural", "zh-CN", "Female"),
        EdgeVoice("zh-TW-HsiaoChenNeural", "zh-TW", "Female"),
        EdgeVoice("nb-NO-PernilleNeural", "nb-NO", "Female"),
    )

    @Test
    fun pickVoiceByLanguageAndGender() {
        assertEquals("bg-BG-KalinaNeural", EdgeProtocol.pickVoice(voices, "bg", VoiceGender.FEMALE)?.shortName)
        assertEquals("bg-BG-BorislavNeural", EdgeProtocol.pickVoice(voices, "bg", VoiceGender.MALE)?.shortName)
        // en -> en-US, класическият глас пред детския AnaNeural
        assertEquals("en-US-AriaNeural", EdgeProtocol.pickVoice(voices, "en", VoiceGender.FEMALE)?.shortName)
        assertEquals("zh-CN-XiaoxiaoNeural", EdgeProtocol.pickVoice(voices, "zh-CN", VoiceGender.FEMALE)?.shortName)
        // няма мъжки норвежки глас -> женският
        assertEquals("nb-NO-PernilleNeural", EdgeProtocol.pickVoice(voices, "nb", VoiceGender.MALE)?.shortName)
        assertNull(EdgeProtocol.pickVoice(voices, "mk", VoiceGender.FEMALE))
    }

    @Test
    fun muidIsHex() {
        assertTrue(EdgeProtocol.muid().matches(Regex("[0-9A-F]{32}")))
    }
}
