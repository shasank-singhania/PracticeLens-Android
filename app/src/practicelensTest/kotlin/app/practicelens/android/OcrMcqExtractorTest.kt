package app.practicelens.android

import app.practicelens.android.ocr.OcrMcqExtractor
import app.practicelens.android.ocr.OcrObservation
import app.practicelens.android.ocr.OcrParser
import app.practicelens.android.ocr.OcrTextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrMcqExtractorTest {
    private val extractor = OcrMcqExtractor()

    @Test fun `extracts labeled question and ignores page chrome`() {
        val observation = observation(
            "Course header",
            "What is the capital of France?",
            "A) Paris",
            "B) Rome",
            "C) Madrid",
            "Next question",
        )

        val extracted = extractor.extract(observation)

        assertNotNull(extracted)
        assertEquals(
            "What is the capital of France?\nA. Paris\nB. Rome\nC. Madrid",
            extracted!!.observation.fullText,
        )
        assertTrue(OcrParser().parse(extracted.observation).valid)
    }

    @Test fun `does not infer unlabeled option rows after a question`() {
        val observation = observation(
            "Practice test",
            "Which planet is closest to the Sun?",
            "Mercury",
            "Venus",
            "Earth",
            "Mars",
        )

        val extracted = extractor.extract(observation)

        assertEquals(null, extracted)
    }

    @Test fun `extracts bullet option rows with consistent geometry`() {
        val observation = observation(
            "Which planet is closest to the Sun?",
            "• Mercury",
            "• Venus",
            "• Earth",
        )

        val extracted = extractor.extract(observation)

        assertNotNull(extracted)
        assertEquals(3, extracted!!.optionCount)
        assertTrue(OcrParser().parse(extracted.observation).valid)
    }

    @Test fun `treats repeated isolated O as bullets only with consistent geometry`() {
        val lines = listOf(
            line("Which value is correct?", 80, 80),
            line("O 2πr", 80, 140),
            line("O πr²", 82, 190),
            line("O random footer", 400, 500),
        )

        val extracted = extractor.extract(OcrObservation(lines.joinToString("\n") { it.text }, lines))

        assertNotNull(extracted)
        assertEquals(2, extracted!!.optionCount)
        assertTrue(extracted.observation.fullText.contains("πr²"))
    }

    @Test fun `excludes second question below first`() {
        val observation = observation(
            "Which animal swims?",
            "A. Fish",
            "B. Horse",
            "Which animal flies?",
            "A. Bird",
            "B. Cat",
        )

        val extracted = extractor.extract(observation)

        assertNotNull(extracted)
        assertTrue(!extracted!!.observation.fullText.contains("Which animal flies?"))
    }

    @Test fun `ordinary paragraph is not treated as an MCQ`() {
        val observation = observation("Chapter heading", "This is ordinary prose without choices.")
        assertEquals(null, extractor.extract(observation))
    }

    @Test fun `isolates centered visual question region from surrounding page text`() {
        val lines = listOf(
            line("Practice Set 12", 40, 20, right = 620),
            line("Earlier unrelated paragraph about history", 44, 72, right = 680),
            line("1. Which graph shows constant velocity?", 72, 220, right = 720),
            line("A. A straight rising line", 92, 292, right = 640),
            line("B. A horizontal line", 92, 350, right = 610),
            line("C. A curve", 92, 408, right = 560),
            line("2. Which graph shows acceleration?", 72, 520, right = 720),
            line("A. Another option", 92, 592, right = 620),
            line("Footer score 4/10", 48, 760, right = 430),
        )

        val extracted = extractor.extract(OcrObservation(lines.joinToString("\n") { it.text }, lines, width = 800, height = 900))

        assertNotNull(extracted)
        assertTrue(extracted!!.observation.fullText.contains("Which graph shows constant velocity?"))
        assertTrue(!extracted.observation.fullText.contains("Practice Set"))
        assertTrue(!extracted.observation.fullText.contains("Which graph shows acceleration?"))
        assertNotNull(extracted.region)
    }

    @Test fun `extracts LMS quiz card with unlabeled radio option rows`() {
        val lines = listOf(
            line("Home My Courses Grades Calendar", 250, 40, right = 1100),
            line("SM Practice Quiz M5", 358, 168, right = 650),
            line("Question 1", 412, 270, right = 470),
            line("In the S&P transition matrix shown in Lesson 2 what does NR stand for?", 512, 270, right = 1018),
            line("Negative rating", 544, 324, right = 680),
            line("Rating withdrawn", 544, 364, right = 710),
            line("Non-rated", 544, 404, right = 650),
            line("Not recorded", 544, 444, right = 670),
            line("Incorrect. There is no negative rating category. NR stands for rating", 514, 484, right = 1010),
            line("withdrawn.", 544, 508, right = 630),
            line("Question 2", 412, 612, right = 470),
            line("In the estimated parameters from Lesson 4 σ1 = 0.839 and σ2 = 3.602.", 512, 612, right = 1020),
            line("What does this difference in standard deviations indicate about the two", 512, 638, right = 1000),
            line("regimes?", 512, 664, right = 590),
            line("Both regimes have similar volatility", 544, 724, right = 800),
            line("The model has estimation errors", 544, 764, right = 790),
            line("The stressed regime (state 2) exhibits much higher volatility in VIX", 544, 804, right = 1030),
            line("changes than the calm regime", 544, 830, right = 790),
            line("The calm regime is more volatile", 544, 870, right = 820),
            line("QUESTIONS", 1080, 250, right = 1180),
            line("x x x x", 1084, 292, right = 1210),
        )

        val extracted = extractor.extract(OcrObservation(lines.joinToString("\n") { it.text }, lines, width = 1622, height = 943))

        assertNotNull(extracted)
        assertEquals(4, extracted!!.optionCount)
        assertTrue(extracted.observation.fullText.contains("What does this difference"))
        assertTrue(extracted.observation.fullText.contains("A. Both regimes have similar volatility"))
        assertTrue(extracted.observation.fullText.contains("C. The stressed regime"))
        assertTrue(extracted.observation.fullText.contains("changes than the calm regime"))
        assertTrue(!extracted.observation.fullText.contains("Incorrect"))
        assertTrue(!extracted.observation.fullText.contains("QUESTIONS"))
        assertTrue(OcrParser().parse(extracted.observation.fullText).valid)
    }

    private fun observation(vararg texts: String): OcrObservation {
        val lines = texts.mapIndexed { index, text ->
            val top = 40 + index * 55
            line(text, 50, top)
        }
        return OcrObservation(texts.joinToString("\n"), lines)
    }

    private fun line(text: String, left: Int, top: Int, right: Int = left + 700) =
        OcrTextLine(text, left, top, right, top + 32)
}
