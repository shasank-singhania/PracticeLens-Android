package app.practicelens.android

import app.practicelens.android.ocr.OcrGuideFilter
import app.practicelens.android.ocr.OcrTextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrGuideFilterTest {
    @Test fun `guide filter is aiming only and preserves reading order`() {
        val filter = OcrGuideFilter(widthFraction = 0.8f, heightFraction = 0.6f)
        val lines = listOf(
            line("Page header", 100, 40),
            line("B. Rome", 100, 350),
            line("What is the capital of France?", 100, 220),
            line("A. Paris", 100, 300),
            line("Page footer", 100, 580),
        )

        val observation = filter.filter(lines, imageWidth = 1000, imageHeight = 600)

        assertEquals(
            "Page header\nWhat is the capital of France?\nA. Paris\nB. Rome\nPage footer",
            observation.fullText,
        )
        assertEquals(5, observation.lines.size)
    }

    @Test fun `invalid image dimensions still produce empty observation`() {
        val observation = OcrGuideFilter().filter(
            listOf(line("Text outside guide", 20, 20)),
            imageWidth = 0,
            imageHeight = 1000,
        )

        assertTrue(observation.fullText.isEmpty())
        assertFalse(observation.lines.any())
    }

    private fun line(text: String, left: Int, top: Int) =
        OcrTextLine(text, left, top, left + 500, top + 30)
}
