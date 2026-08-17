package app.practicelens.android.ocr

import app.practicelens.android.core.PracticeOption
import app.practicelens.android.core.PracticeQuestion
import app.practicelens.android.core.CapturedQuestionMedia

data class OcrTextLine(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val elements: List<OcrTextElement> = emptyList(),
    val confidence: Float? = null,
    val recognizedLanguage: String? = null,
)

data class OcrTextElement(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val confidence: Float? = null,
)

data class OcrTextBlock(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val lines: List<OcrTextLine> = emptyList(),
    val recognizedLanguage: String? = null,
)

data class OcrObservation(
    val fullText: String,
    val lines: List<OcrTextLine> = fullText.lines().filter { it.isNotBlank() }.map { OcrTextLine(it) },
    val blocks: List<OcrTextBlock> = emptyList(),
    val rotationDegrees: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
)

data class OcrOptionDraft(
    val label: String,
    val text: String,
    val labelError: String? = null,
    val textError: String? = null,
)

data class OcrDraft(
    val question: String,
    val options: List<OcrOptionDraft>,
    val valid: Boolean,
    val message: String,
    val rawText: String,
    val rawObservation: OcrObservation? = null,
    val confidence: Double = if (valid) 1.0 else 0.0,
    val warnings: List<String> = emptyList(),
    val lineCount: Int = rawText.lines().count { it.isNotBlank() },
) {
    fun toPracticeQuestion(media: CapturedQuestionMedia? = null): PracticeQuestion {
        require(valid) { message }
        return PracticeQuestion(
            prompt = question.trim(),
            options = options.mapIndexed { index, option -> PracticeOption("option-$index", option.text.trim()) },
            media = media,
            ocrText = rawText.takeIf(String::isNotBlank),
        )
    }
}

class OcrParser {
    private val extractor = OcrMcqExtractor()
    private val labelPattern = Regex(
        pattern = """^\s*(?:[\(\[]?\s*([A-Ha-h1-8])\s*[\)\].:\-]|([A-Ha-h1-8])\s*[\)\].:\-])\s*(.*)$""",
    )

    fun parse(observation: OcrObservation): OcrDraft {
        val extraction = extractor.extract(observation)
        if (extraction == null) {
            return OcrDraft(
                question = observation.fullText.trim(),
                options = emptyList(),
                valid = false,
                message = "Needs manual review: ${extractor.rejectionReason(observation)}",
                rawText = observation.fullText,
                rawObservation = observation,
                confidence = 0.0,
            )
        }
        val draft = parse(extraction.observation.fullText)
        return draft.copy(
            rawObservation = observation,
            confidence = extraction.confidence,
            warnings = extraction.warnings,
        )
    }

    fun parse(text: String): OcrDraft {
        val rawLines = text.replace('\u00a0', ' ').lines()
        val lines = rawLines.map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return invalid("", emptyList(), "No readable question text.", text)

        val firstOptionIndex = lines.indexOfFirst { labelPattern.matches(it) }
        if (firstOptionIndex <= 0) {
            return invalid(lines.joinToString("\n").trim(), emptyList(), "Need a question followed by labeled options.", text)
        }

        val question = lines.take(firstOptionIndex).joinToString("\n") { it.trim() }.trim()
        if (question.isBlank()) return invalid("", emptyList(), "Question prompt is missing.", text)

        val options = mutableListOf<OcrOptionDraft>()
        val ambiguous = mutableListOf<String>()
        var currentLabel: String? = null
        val currentText = StringBuilder()

        fun flush() {
            val label = currentLabel ?: return
            options += OcrOptionDraft(label = normalizeLabel(label), text = currentText.toString().trim())
            currentLabel = null
            currentText.clear()
        }

        lines.drop(firstOptionIndex).forEach { line ->
            val match = labelPattern.matchEntire(line)
            if (match != null) {
                flush()
                val label = match.groupValues[1].ifBlank { match.groupValues[2] }
                currentLabel = label
                val optionText = match.groupValues[3].trim()
                if (optionText.isNotBlank()) currentText.append(optionText)
            } else if (currentLabel != null) {
                if (looksLikeAmbiguousBoundary(line)) {
                    ambiguous += "Ambiguous option boundary: ${line.trim()}"
                } else {
                    if (currentText.isNotEmpty()) currentText.append('\n')
                    currentText.append(line.trim())
                }
            } else {
                ambiguous += "Unlabeled option text: ${line.trim()}"
            }
        }
        flush()

        val validated = validateOptions(options)
        val messages = ambiguous + validated.messages
        val valid = messages.isEmpty()
        return OcrDraft(
            question = question,
            options = validated.options,
            valid = valid,
            message = if (valid) "Parsed" else messages.joinToString("\n"),
            rawText = text,
        )
    }

    fun validate(question: String, options: List<OcrOptionDraft>, rawText: String = ""): OcrDraft {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isBlank()) {
            return invalid(trimmedQuestion, options, "Question prompt is missing.", rawText)
        }
        val validated = validateOptions(options)
        return OcrDraft(
            question = trimmedQuestion,
            options = validated.options,
            valid = validated.messages.isEmpty(),
            message = if (validated.messages.isEmpty()) "Parsed" else validated.messages.joinToString("\n"),
            rawText = rawText,
        )
    }

    private fun validateOptions(options: List<OcrOptionDraft>): Validation {
        val messages = mutableListOf<String>()
        if (options.size < 2) messages += "Need at least two labeled options."
        if (options.size > 8) messages += "No more than eight options are supported."

        val normalized = options.map { option ->
            val label = normalizeLabel(option.label)
            val labelError = when {
                label.isBlank() -> "Label is required."
                !label.matches(Regex("[A-H1-8]")) -> "Use A-H or 1-8."
                else -> null
            }
            val textError = if (option.text.trim().isBlank()) "Option text is required." else null
            if (labelError != null) messages += "Invalid label '${option.label}'."
            if (textError != null) messages += "Option $label is missing text."
            option.copy(label = label, text = option.text.trim(), labelError = labelError, textError = textError)
        }
        val duplicates = normalized.groupBy { it.label }.filter { it.key.isNotBlank() && it.value.size > 1 }.keys
        if (duplicates.isNotEmpty()) messages += "Duplicate option labels: ${duplicates.joinToString()}."
        return Validation(normalized, messages.distinct())
    }

    private fun normalizeLabel(label: String): String = label.trim().uppercase()

    private fun looksLikeAmbiguousBoundary(line: String): Boolean =
        Regex("""^\s*[A-Za-z0-9][\)\].:\-]?\s+\S+.*$""").matches(line) &&
            !labelPattern.matches(line) &&
            line.trim().length < 80

    private fun invalid(question: String, options: List<OcrOptionDraft>, message: String, rawText: String) =
        OcrDraft(question = question, options = options, valid = false, message = message, rawText = rawText)

    private data class Validation(val options: List<OcrOptionDraft>, val messages: List<String>)
}
