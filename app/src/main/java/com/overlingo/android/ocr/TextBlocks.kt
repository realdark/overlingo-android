package com.overlingo.android.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A rectangle in photo pixels (no Android classes - so it can be tested on the JVM). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = max(0, width).toLong() * max(0, height)

    fun union(other: Box) = Box(
        min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom)
    )

    fun verticalOverlap(other: Box): Int = min(bottom, other.bottom) - max(top, other.top)

    fun horizontalOverlap(other: Box): Int = min(right, other.right) - max(left, other.left)

    fun intersectionArea(other: Box): Long =
        max(0, horizontalOverlap(other)).toLong() * max(0, verticalOverlap(other))
}

/** A single word and its position in the photo. */
data class OcrWord(val text: String, val box: Box)

/** A line (or part of a line) - words are ordered left to right. */
data class TextLine(val words: List<OcrWord>) {
    val box: Box = words.map { it.box }.reduce(Box::union)
    val text: String = words.joinToString(" ") { it.text }

    /** Average word height - more accurate than box.height with letters like "g", "É". */
    val wordHeight: Float = words.map { it.box.height }.average().toFloat()
}

/**
 * A block of text in the photo - one translation box. It may contain several
 * paragraphs (stacked in a single column); they are translated separately and
 * shown separated by a blank line.
 */
data class TextBlock(val paragraphs: List<List<TextLine>>) {
    val lines: List<TextLine> = paragraphs.flatten()
    val box: Box = lines.map { it.box }.reduce(Box::union)
    val lineHeight: Float = lines.map { it.wordHeight }.average().toFloat()

    /** The text of each paragraph (lines joined). */
    val paragraphTexts: List<String> = paragraphs.map { paragraph -> joinLines(paragraph.map { it.text }) }
    val text: String = paragraphTexts.joinToString(PARAGRAPH_SEPARATOR)

    companion object {
        const val PARAGRAPH_SEPARATOR = "\n\n"

        /** A single-paragraph block. */
        fun of(lines: List<TextLine>) = TextBlock(listOf(lines))

        /** Joins the lines; a hyphenated word ("trans-" + "lation") is put back together. */
        fun joinLines(lines: List<String>): String {
            val sb = StringBuilder()
            for (line in lines) {
                if (sb.isEmpty()) {
                    sb.append(line)
                    continue
                }
                val hyphenated = sb.length > 1 && sb.last() == '-' && sb[sb.length - 2].isLetter()
                if (hyphenated && line.firstOrNull()?.isLowerCase() == true) {
                    sb.setLength(sb.length - 1)
                    sb.append(line)
                } else {
                    sb.append(' ').append(line)
                }
            }
            return sb.toString()
        }
    }
}

/**
 * Cleans up the ML Kit blocks before translation:
 *  - overlapping blocks are merged (otherwise their translations are drawn
 *    on top of each other);
 *  - "text" with fewer than 2 letters is dropped (icons, dice, noise);
 *  - paragraphs of the same text (stacked in one column, with similar
 *    letter size) become one box - aligned left, right OR centred (ML Kit
 *    often splits centred text and text that wraps around a picture);
 *    separate labels and headings stay separate;
 *  - pieces of one paragraph (normal line spacing between them) are joined
 *    back into one paragraph, not separated by a blank line;
 *  - sorts them top to bottom, left to right.
 */
object BlockCleaner {

    fun clean(blocks: List<TextBlock>): List<TextBlock> {
        var current = mergeWhile(blocks, ::overlapping)
            .filter { block -> block.text.count { it.isLetter() } >= 2 }
        // Merging paragraphs can make a block grow over another one (a short centred line
        // between two paragraphs) - so both steps repeat until nothing changes.
        while (true) {
            val next = mergeWhile(mergeWhile(current, ::sameColumnParagraphs), ::overlapping)
            val changed = next.size != current.size
            current = next
            if (!changed) break
        }
        return current
            .map(::joinContinuedParagraphs)
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    /** Overlap of more than 30% of the smaller block. */
    private fun overlapping(a: TextBlock, b: TextBlock): Boolean {
        val smaller = min(a.box.area, b.box.area)
        return smaller > 0 && a.box.intersectionArea(b.box) > 0.3 * smaller
    }

    /** Two paragraphs of the same text: stacked, close together, in one column, with similar letter size. */
    private fun sameColumnParagraphs(a: TextBlock, b: TextBlock): Boolean {
        val (upper, lower) = if (a.box.top <= b.box.top) a to b else b to a
        val h = max(upper.lineHeight, lower.lineHeight)
        if (h / min(upper.lineHeight, lower.lineHeight) > 1.3f) return false // heading and body text - keep separate

        val gap = lower.box.top - upper.box.bottom
        if (gap < -0.5f * h || gap > 2.5f * h) return false

        val narrower = min(upper.box.width, lower.box.width)
        val sameColumn = upper.box.horizontalOverlap(lower.box) >= 0.6f * narrower
        return sameColumn && aligned(upper.box, lower.box, 1.5f * h)
    }

    /** Same left edge, same right edge or same centre (centred text). */
    internal fun aligned(a: Box, b: Box, tolerance: Float): Boolean =
        abs(a.left - b.left) <= tolerance ||
            abs(a.right - b.right) <= tolerance ||
            abs((a.left + a.right) - (b.left + b.right)) / 2f <= tolerance

    /**
     * ML Kit sometimes cuts one paragraph into several blocks. After merging, pieces
     * with normal line spacing between them (not a paragraph gap) become one paragraph
     * again - so it is translated as one sentence, without a blank line in the middle.
     */
    private fun joinContinuedParagraphs(block: TextBlock): TextBlock {
        if (block.paragraphs.size < 2) return block
        val h = block.lineHeight
        val result = mutableListOf<List<TextLine>>()
        for (paragraph in block.paragraphs.sortedBy { it.first().box.top }) {
            val previous = result.lastOrNull()
            if (previous != null && continues(previous, paragraph, h)) {
                result[result.size - 1] = previous + paragraph
            } else {
                result += paragraph
            }
        }
        return if (result.size == block.paragraphs.size) block else TextBlock(result)
    }

    private fun continues(upper: List<TextLine>, lower: List<TextLine>, h: Float): Boolean {
        val upperBox = upper.map { it.box }.reduce(Box::union)
        val lowerBox = lower.map { it.box }.reduce(Box::union)
        val gap = lowerBox.top - upperBox.bottom
        val narrower = min(upperBox.width, lowerBox.width)
        return gap >= -0.5f * h && gap < 0.7f * h &&
            upperBox.horizontalOverlap(lowerBox) >= 0.5f * narrower
    }

    /** Merges pairs of blocks as long as there are any for which [shouldMerge] is true. */
    private fun mergeWhile(blocks: List<TextBlock>, shouldMerge: (TextBlock, TextBlock) -> Boolean): List<TextBlock> {
        val result = blocks.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in result.indices) {
                for (j in i + 1 until result.size) {
                    if (shouldMerge(result[i], result[j])) {
                        result[i] = TextBlock((result[i].paragraphs + result[j].paragraphs).sortedBy { it.first().box.top })
                        result.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
        }
        return result
    }
}
