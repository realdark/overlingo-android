package com.overlingo.android.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockCleanerTest {

    /** A block of lines; each line is words of height h, starting at (x, y). */
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
        // Two paragraphs stacked in one column with a small gap - one box.
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
    /** A block whose lines start at different x (centred or ragged text); line spacing 26, height 20. */
    private fun ragged(y: Int, vararg lines: Pair<Int, String>, h: Int = 20): TextBlock =
        TextBlock.of(lines.mapIndexed { i, (x, text) ->
            var cursor = x
            TextLine(text.split(" ").map { w ->
                OcrWord(w, Box(cursor, y + i * 26, cursor + w.length * h / 2, y + i * 26 + h))
                    .also { cursor = it.box.right + h / 3 }
            })
        })

    @Test
    fun centredParagraphsBecomeOneBlock() {
        // Centred text: the lines start at different x, the centres match.
        val first = ragged(10, 100 to "The sound of a gunshot from", 60 to "the penthouse briefly drowns out")
        val second = ragged(10 + 52 + 20, 140 to "What is going on?")
        assertEquals(1, BlockCleaner.clean(listOf(first, second)).size)
    }

    @Test
    fun textWrappedAroundAPictureBecomesOneBlock() {
        // The right edge is the same, the left one moves (a picture on the left), and ML Kit
        // cut it into blocks - one paragraph, one box, no blank line in the middle.
        val top = ragged(10, 0 to "I got to the theatre around noon and", 0 to "went to say hello to Mr Winsley today")
        // Narrower lines, pushed right by the picture: only the right edge matches.
        val bottom = ragged(10 + 52 + 6, 180 to "ask him for money", 182 to "he is paid little")
        assertEquals(true, kotlin.math.abs(top.box.right - bottom.box.right) < 30)
        assertEquals(true, kotlin.math.abs(top.box.left - bottom.box.left) > 30)
        val result = BlockCleaner.clean(listOf(top, bottom))
        assertEquals(1, result.size)
        assertEquals(1, result[0].paragraphs.size)
    }

    @Test
    fun shortCentredLineBetweenParagraphsJoinsTheText() {
        // Like the photo: a short centred line that ML Kit put in its own block, between
        // the paragraph above and its continuation below.
        val above = ragged(10, 40 to "the bosses henchmen begin to look worried.", 160 to "What is going on?")
        val line = ragged(10 + 52 + 20, 100 to "You give the order for the officers")
        val below = ragged(10 + 52 + 20 + 26, 60 to "to move in. Swiftly the henchmen make", 120 to "their escape while the bosses")
        val result = BlockCleaner.clean(listOf(above, line, below))
        assertEquals(1, result.size)
        assertEquals(2, result[0].paragraphs.size)
        assertEquals(true, result[0].paragraphTexts[1].startsWith("You give the order for the officers to move in."))
    }

    @Test
    fun alignment() {
        val a = Box(100, 0, 500, 20)
        assertEquals(true, BlockCleaner.aligned(a, Box(105, 30, 300, 50), 30f))   // left
        assertEquals(true, BlockCleaner.aligned(a, Box(300, 30, 495, 50), 30f))   // right
        assertEquals(true, BlockCleaner.aligned(a, Box(200, 30, 400, 50), 30f))   // centre
        assertEquals(false, BlockCleaner.aligned(a, Box(0, 30, 200, 50), 30f))
    }
}
