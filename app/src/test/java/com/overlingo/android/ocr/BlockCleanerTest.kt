package com.overlingo.android.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockCleanerTest {

    /** Блок от редове; всеки ред - думи с височина h, започващ от (x, y). */
    private fun block(x: Int, y: Int, vararg lines: String, h: Int = 20): TextBlock =
        TextBlock.of(lines.mapIndexed { i, text ->
            var cursor = x
            TextLine(text.split(" ").map { w ->
                OcrWord(w, Box(cursor, y + i * 26, cursor + w.length * h / 2, y + i * 26 + h))
                    .also { cursor = it.box.right + h / 3 }
            })
        })

    @Test
    fun separateBlocksStaySeparate() {
        val blocks = BlockCleaner.clean(listOf(block(10, 10, "Title"), block(10, 200, "Body text")))
        assertEquals(listOf("Title", "Body text"), blocks.map { it.text })
    }

    @Test
    fun overlappingBlocksAreMerged() {
        val big = block(10, 10, "Some stories stay with us", "from childhood we remember")
        val small = block(40, 30, "remember them")
        val result = BlockCleaner.clean(listOf(big, small))
        assertEquals(1, result.size)
        assertEquals(3, result[0].lines.size)
    }

    @Test
    fun noiseIsDropped() {
        val result = BlockCleaner.clean(listOf(block(10, 10, "Real text"), block(400, 400, "Q"), block(400, 600, "8")))
        assertEquals(listOf("Real text"), result.map { it.text })
    }

    @Test
    fun readingOrder() {
        val result = BlockCleaner.clean(listOf(block(10, 300, "Second"), block(10, 10, "First")))
        assertEquals(listOf("First", "Second"), result.map { it.text })
    }

    @Test
    fun linesAreJoinedAndHyphenationRemoved() {
        assertEquals("translation works", TextBlock.joinLines(listOf("transla-", "tion works")))
        assertEquals("Coca- Cola", TextBlock.joinLines(listOf("Coca-", "Cola")))
        assertEquals("one two three", block(0, 0, "one two", "three").text)
    }

    @Test
    fun paragraphsOfOneTextBecomeOneBlock() {
        // Два абзаца един под друг в една колона, с малко разстояние - един прозорец.
        val first = block(10, 10, "First paragraph line one", "and line two of it.")
        val second = block(10, 10 + 52 + 14, "Second paragraph starts", "here and ends.")
        val result = BlockCleaner.clean(listOf(first, second))
        assertEquals(1, result.size)
        assertEquals(
            listOf("First paragraph line one and line two of it.", "Second paragraph starts here and ends."),
            result[0].paragraphTexts,
        )
        assertEquals(result[0].paragraphTexts.joinToString("\n\n"), result[0].text)
    }

    @Test
    fun headingStaysSeparateFromBody() {
        val heading = block(10, 10, "GAME OVERVIEW", h = 40)
        val body = block(10, 70, "In this detective adventure", "for one to four players")
        assertEquals(2, BlockCleaner.clean(listOf(heading, body)).size)
    }

    @Test
    fun farApartOrSideBySideTextsStaySeparate() {
        val top = block(10, 10, "Rulebook")
        val farBelow = block(10, 400, "Case deck boxes")
        val sideBySide = block(600, 10, "Action dice")
        assertEquals(3, BlockCleaner.clean(listOf(top, farBelow, sideBySide)).size)
    }
}
