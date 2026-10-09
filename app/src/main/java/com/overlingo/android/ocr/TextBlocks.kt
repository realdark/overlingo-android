package com.overlingo.android.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Правоъгълник в пиксели на снимката (без Android класове - за да се тества на JVM). */
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

/** Една дума и позицията ѝ на снимката. */
data class OcrWord(val text: String, val box: Box)

/** Ред (или парче от ред) - думите са подредени отляво надясно. */
data class TextLine(val words: List<OcrWord>) {
    val box: Box = words.map { it.box }.reduce(Box::union)
    val text: String = words.joinToString(" ") { it.text }

    /** Средна височина на думите - по-точна от box.height при букви като "g", "Й". */
    val wordHeight: Float = words.map { it.box.height }.average().toFloat()
}

/**
 * Блок текст върху снимката - един прозорец с превод. Може да съдържа няколко
 * абзаца (един под друг, в една колона); те се превеждат поотделно и се
 * показват разделени с празен ред.
 */
data class TextBlock(val paragraphs: List<List<TextLine>>) {
    val lines: List<TextLine> = paragraphs.flatten()
    val box: Box = lines.map { it.box }.reduce(Box::union)
    val lineHeight: Float = lines.map { it.wordHeight }.average().toFloat()

    /** Текстът на всеки абзац (редовете слепени). */
    val paragraphTexts: List<String> = paragraphs.map { paragraph -> joinLines(paragraph.map { it.text }) }
    val text: String = paragraphTexts.joinToString(PARAGRAPH_SEPARATOR)

    companion object {
        const val PARAGRAPH_SEPARATOR = "\n\n"

        /** Блок от един абзац. */
        fun of(lines: List<TextLine>) = TextBlock(listOf(lines))

        /** Слепва редовете; пренесена дума ("пре-" + "вод") се събира отново. */
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
 * Почиства блоковете от ML Kit преди превода:
 *  - блокове, които се застъпват, се сливат (иначе преводите им се рисуват
 *    един върху друг);
 *  - отпада "текст" с по-малко от 2 букви (иконки, зарове, шум);
 *  - абзаците на един и същ текст (един под друг, в една колона, с подобни
 *    букви) стават един прозорец; отделни надписи и заглавия остават отделно;
 *  - подрежда ги отгоре надолу, отляво надясно.
 */
object BlockCleaner {

    fun clean(blocks: List<TextBlock>): List<TextBlock> {
        val withoutNoise = mergeWhile(blocks, ::overlapping)
            .filter { block -> block.text.count { it.isLetter() } >= 2 }
        return mergeWhile(withoutNoise, ::sameColumnParagraphs)
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    /** Застъпване над 30% от по-малкия блок. */
    private fun overlapping(a: TextBlock, b: TextBlock): Boolean {
        val smaller = min(a.box.area, b.box.area)
        return smaller > 0 && a.box.intersectionArea(b.box) > 0.3 * smaller
    }

    /** Два абзаца от един текст: един под друг, близо, в една колона, с подобен размер на буквите. */
    private fun sameColumnParagraphs(a: TextBlock, b: TextBlock): Boolean {
        val (upper, lower) = if (a.box.top <= b.box.top) a to b else b to a
        val h = max(upper.lineHeight, lower.lineHeight)
        if (h / min(upper.lineHeight, lower.lineHeight) > 1.3f) return false // заглавие и текст - отделно

        val gap = lower.box.top - upper.box.bottom
        if (gap < -0.5f * h || gap > 2.5f * h) return false

        val narrower = min(upper.box.width, lower.box.width)
        val sameColumn = upper.box.horizontalOverlap(lower.box) >= 0.6f * narrower
        val alignedLeft = abs(upper.box.left - lower.box.left) <= 1.5f * h
        return sameColumn && alignedLeft
    }

    /** Слива двойки блокове, докато има такива, за които [shouldMerge] е вярно. */
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
