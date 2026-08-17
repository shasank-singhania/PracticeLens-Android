package app.practicelens.android

import app.practicelens.android.ocr.OcrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrParserCorpusTest {
    private val parser = OcrParser()

    @Test fun `letter numeric and roman labels parse when explicitly marked`() {
        listOf(
            "Stem\nA. Alpha\nB. Beta",
            "Stem\nA) Alpha\nB) Beta",
            "Stem\nA: Alpha\nB: Beta",
            "Stem\n(A) Alpha\n(B) Beta",
            "Stem\n1. Alpha\n2. Beta",
            "Stem\nI. Alpha\nII. Beta",
        ).forEach { text ->
            val draft = parser.parse(text)
            assertTrue(text, draft.valid)
            assertEquals(2, draft.options.size)
        }
    }

    @Test fun `wrapped stems and wrapped options preserve line breaks`() {
        val draft = parser.parse(
            """
            Which statement is NOT supported
            by the data shown below
            A. The value is 12.5%
            after the discount
            B. The value is -3.14 dollars
            after tax
            """.trimIndent(),
        )

        assertTrue(draft.message, draft.valid)
        assertEquals("Which statement is NOT supported\nby the data shown below", draft.question)
        assertEquals("The value is 12.5%\nafter the discount", draft.options[0].text)
        assertEquals("The value is -3.14 dollars\nafter tax", draft.options[1].text)
    }

    @Test fun `math unicode and punctuation are preserved`() {
        val draft = parser.parse("Find α when x² + β = 0\nA. α = -β\nB. α = (β)")

        assertTrue(draft.message, draft.valid)
        assertEquals("Find α when x² + β = 0", draft.question)
        assertEquals("α = -β", draft.options[0].text)
        assertEquals("α = (β)", draft.options[1].text)
    }

    @Test fun `headers footers and one complete nearby question select one block safely`() {
        val draft = parser.parse(
            """
            Page 12
            7. Which number is prime?
            A. 9
            B. 11
            C. 15
            D. 21
            8. Next question begins
            A. first
            Footer
            """.trimIndent(),
        )

        assertFalse(draft.valid)
        assertTrue(draft.message.contains("Duplicate option labels"))
    }

    @Test fun `unlabeled bullets incomplete crops noise and empty OCR fail safely`() {
        listOf(
            "Question\n○ Alpha\n○ Beta\n○ Gamma",
            "Question\nAlpha\nBeta\nGamma\nDelta",
            "Question\nA. only one option",
            "%%%\n@ random\nnoise",
            "",
        ).forEach { text ->
            val draft = parser.parse(text)
            assertFalse(text, draft.valid)
        }
    }

    @Test fun `sentence continuation is not converted into missing option`() {
        val draft = parser.parse(
            """
            Which claim is EXCEPT true?
            A. First part
            continues as a sentence
            B. Second part
            """.trimIndent(),
        )

        assertTrue(draft.message, draft.valid)
        assertEquals("First part\ncontinues as a sentence", draft.options[0].text)
        assertEquals(2, draft.options.size)
    }
}
