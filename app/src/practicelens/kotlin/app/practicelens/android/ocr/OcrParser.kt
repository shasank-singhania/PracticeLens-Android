package app.practicelens.android.ocr

import app.practicelens.android.core.PracticeOption
import app.practicelens.android.core.PracticeQuestion

data class OcrParseResult(
    val question: PracticeQuestion?,
    val ambiguous: Boolean,
    val message: String,
)

class OcrParser {
    private val optionPattern = Regex("""(?m)^\s*([A-Ha-h1-8])[\).:\-]\s+(.+)$""")

    fun parse(text: String): OcrParseResult {
        val cleaned = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return OcrParseResult(null, true, "No readable question text.")
        val matches = cleaned.mapNotNull { line -> optionPattern.find(line)?.let { it.groupValues[1].uppercase() to it.groupValues[2].trim() } }
        if (matches.size !in 2..8) return OcrParseResult(null, true, "Need 2 to 8 labeled options.")
        val firstOptionIndex = cleaned.indexOfFirst { optionPattern.containsMatchIn(it) }
        val prompt = cleaned.take(firstOptionIndex).joinToString("\n").trim()
        if (prompt.isBlank()) return OcrParseResult(null, true, "Question prompt is missing.")
        val options = matches.map { PracticeOption(it.first, it.second) }
        if (options.map { it.id }.distinct().size != options.size) {
            return OcrParseResult(null, true, "Duplicate option labels.")
        }
        return OcrParseResult(PracticeQuestion(prompt = prompt, options = options), false, "Parsed")
    }
}
