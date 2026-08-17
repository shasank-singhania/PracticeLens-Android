package app.practicelens.android.interpretation

import app.practicelens.android.core.CapturedQuestionMedia
import app.practicelens.android.ocr.OcrDraft
import app.practicelens.android.ocr.OcrOptionDraft
import app.practicelens.android.ocr.OcrParser
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.delay

enum class InterpretationStatus { READY, RETAKE_REQUIRED, NOT_MCQ }

enum class PracticeLensError {
    OFFLINE,
    TIMEOUT,
    TEMPORARY_SERVICE_FAILURE,
    QUOTA_EXCEEDED,
    APP_CHECK_REJECTED,
    FIREBASE_NOT_CONFIGURED,
    INVALID_MODEL_RESPONSE,
    IMAGE_UNAVAILABLE,
    CANCELLED,
    UNKNOWN,
}

data class InterpretedOption(val position: Int, val displayLabel: String, val text: String)

data class QuestionInterpretation(
    val status: InterpretationStatus,
    val questionText: String,
    val options: List<InterpretedOption>,
    val confidence: Double,
    val retakeReason: String? = null,
    val warnings: List<String> = emptyList(),
) {
    fun toOcrDraft(rawText: String): OcrDraft =
        OcrParser().validate(questionText, options.map { OcrOptionDraft(it.displayLabel, it.text) }, rawText)
}

interface QuestionImageInterpreter {
    suspend fun interpret(image: CapturedQuestionMedia, optionalOcrText: String?): QuestionInterpretation
}

class DemoQuestionImageInterpreter : QuestionImageInterpreter {
    override suspend fun interpret(image: CapturedQuestionMedia, optionalOcrText: String?): QuestionInterpretation {
        delay(80)
        return QuestionInterpretation(
            status = InterpretationStatus.RETAKE_REQUIRED,
            questionText = "",
            options = emptyList(),
            confidence = 0.0,
            retakeReason = "Demo builds stay offline. Edit the OCR draft manually or use a production build configured for Firebase AI image analysis.",
        )
    }
}

class ModelResponseException(val error: PracticeLensError, message: String) : Exception(message)

object QuestionInterpretationValidator {
    private val gson = Gson()

    fun parseJson(json: String): QuestionInterpretation {
        val dto = try {
            gson.fromJson(json.take(64_000), InterpretationDto::class.java)
        } catch (e: JsonSyntaxException) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Malformed interpretation JSON.")
        } ?: throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Blank interpretation JSON.")
        val status = runCatching { InterpretationStatus.valueOf(dto.status.orEmpty()) }.getOrNull()
            ?: throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Unknown interpretation status.")
        val confidence = dto.confidence
        if (!confidence.isFinite() || confidence !in 0.0..1.0) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Invalid interpretation confidence.")
        }
        val options = dto.options.orEmpty().map {
            InterpretedOption(it.position, it.displayLabel.orEmpty().trim(), it.text.orEmpty().trim())
        }
        if (status == InterpretationStatus.READY) {
            validateReady(dto.questionText.orEmpty(), options)
        }
        return QuestionInterpretation(
            status = status,
            questionText = dto.questionText.orEmpty().trim(),
            options = options,
            confidence = confidence,
            retakeReason = dto.retakeReason?.trim()?.takeIf(String::isNotBlank),
            warnings = dto.warnings.orEmpty().map(String::trim).filter(String::isNotBlank),
        )
    }

    private fun validateReady(questionText: String, options: List<InterpretedOption>) {
        if (questionText.isBlank()) throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "READY interpretation needs a question.")
        if (options.size !in 2..8) throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "READY interpretation needs 2-8 options.")
        val positions = options.map { it.position }
        if (positions != options.indices.toList()) throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Option positions must be contiguous visual order.")
        if (options.any { it.text.isBlank() }) throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Option text is blank.")
        if (options.map { it.text.lowercase() }.distinct().size != options.size) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Duplicate option text.")
        }
    }

    private data class InterpretationDto(
        val status: String?,
        val questionText: String?,
        val options: List<OptionDto>?,
        val confidence: Double,
        val retakeReason: String?,
        val warnings: List<String>?,
    )

    private data class OptionDto(
        val position: Int,
        val displayLabel: String?,
        val text: String?,
    )
}
