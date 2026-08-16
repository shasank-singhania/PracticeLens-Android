package app.practicelens.android

import app.practicelens.android.ocr.OcrOptionDraft
import app.practicelens.android.ocr.OcrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrParserTest {
    private val parser = OcrParser()

    @Test fun `parses common option prefixes`() {
        val prefixes = listOf("A.", "A)", "A:", "A-", "(A)", "1.", "1)", "1:")
        prefixes.forEach { prefix ->
            val second = if (prefix.first().isDigit()) "2)" else "B)"
            val draft = parser.parse("Pick one\n$prefix Alpha\n$second Beta")
            assertTrue("prefix=$prefix ${draft.message}", draft.valid)
            assertEquals(2, draft.options.size)
        }
    }

    @Test fun `parses multiline question and options`() {
        val draft = parser.parse(
            """
            Which expression is equivalent
            to x^2 - 4?
            A. (x - 2)
            (x + 2)
            B. x^2 + 4
            """.trimIndent(),
        )

        assertTrue(draft.message, draft.valid)
        assertEquals("Which expression is equivalent\nto x^2 - 4?", draft.question)
        assertEquals("(x - 2)\n(x + 2)", draft.options.first().text)
    }

    @Test fun `rejects duplicate labels and too few options`() {
        assertFalse(parser.parse("Question\nA. One\nA. Two").valid)
        assertFalse(parser.parse("Question\nA. One").valid)
    }

    @Test fun `rejects more than eight options`() {
        val text = buildString {
            appendLine("Question")
            ('A'..'H').forEach { appendLine("$it. Option $it") }
            appendLine("1. Ninth")
        }
        assertFalse(parser.parse(text).valid)
    }

    @Test fun `ambiguous unlabeled boundary stays editable invalid`() {
        val draft = parser.parse("Question\nA. One\nB Two without punctuation")
        assertFalse(draft.valid)
        assertTrue(draft.message.contains("Ambiguous"))
        assertEquals("Question", draft.question)
    }

    @Test fun `preserves punctuation and learner correction produces valid question`() {
        val invalid = parser.parse("Question\nA. y = mx + b\nB.")
        assertFalse(invalid.valid)

        val corrected = parser.validate(
            question = invalid.question,
            options = listOf(OcrOptionDraft("A", "y = mx + b"), OcrOptionDraft("B", "y - b = mx")),
            rawText = invalid.rawText,
        )

        assertTrue(corrected.message, corrected.valid)
        assertEquals("y = mx + b", corrected.toPracticeQuestion().options.first().text)
    }
}
