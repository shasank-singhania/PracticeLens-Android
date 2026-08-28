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
enum class AutomaticAnswerStatus { ANSWERED, UNREADABLE, NO_SINGLE_MCQ, UNSUPPORTED }
enum class AnswerBackend { GEMINI, SIMULATED }
enum class ModelRequestState { IDLE, SENDING, WAITING, SUCCESS, ERROR }

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

data class AutomaticAnswer(
    val status: AutomaticAnswerStatus,
    val questionText: String? = null,
    val options: List<InterpretedOption> = emptyList(),
    val selectedOptionIndex: Int? = null,
    val answerLabel: String? = null,
    val answerText: String? = null,
    val explanation: String? = null,
    val confidence: Double,
    val imageReadable: Boolean? = null,
    val answerable: Boolean? = null,
    val simulated: Boolean = false,
    val questionSummary: String? = questionText,
)

interface QuestionImageInterpreter {
    suspend fun interpret(image: CapturedQuestionMedia, optionalOcrText: String?): QuestionInterpretation
    suspend fun answerFromImage(
        image: CapturedQuestionMedia,
        includeExplanation: Boolean,
        optionalOcrText: String? = null,
    ): AutomaticAnswer
}

interface QuestionImageGateway {
    val backend: AnswerBackend
    val modelId: String
    suspend fun interpret(image: CapturedQuestionMedia, optionalOcrText: String?): String
    suspend fun answer(image: CapturedQuestionMedia, includeExplanation: Boolean, optionalOcrText: String?): String
}

data class AnswerBackendPolicy(
    val defaultBackend: AnswerBackend,
    val geminiEnabled: Boolean,
    val simulatedEnabled: Boolean,
    val modelId: String,
) {
    fun isAllowed(backend: AnswerBackend): Boolean =
        when (backend) {
            AnswerBackend.GEMINI -> geminiEnabled
            AnswerBackend.SIMULATED -> simulatedEnabled
        }

    fun allowedBackends(): List<AnswerBackend> =
        AnswerBackend.values().filter(::isAllowed)
}

class RoutingQuestionImageInterpreter(
    private val geminiGateway: QuestionImageGateway?,
    private val simulatedInterpreter: QuestionImageInterpreter,
    private val selectedBackend: () -> AnswerBackend,
    private val timeoutMs: Long = 20_000,
) : QuestionImageInterpreter {
    override suspend fun interpret(image: CapturedQuestionMedia, optionalOcrText: String?): QuestionInterpretation {
        val gateway = geminiGateway ?: return simulatedInterpreter.interpret(image, optionalOcrText)
        val json = kotlinx.coroutines.withTimeout(timeoutMs) { gateway.interpret(image, optionalOcrText) }
        return QuestionInterpretationValidator.parseJson(json)
    }

    override suspend fun answerFromImage(
        image: CapturedQuestionMedia,
        includeExplanation: Boolean,
        optionalOcrText: String?,
    ): AutomaticAnswer =
        when (selectedBackend()) {
            AnswerBackend.GEMINI -> {
                val gateway = geminiGateway
                    ?: throw ModelResponseException(PracticeLensError.FIREBASE_NOT_CONFIGURED, "Gemini backend is unavailable.")
                val json = kotlinx.coroutines.withTimeout(timeoutMs) {
                    gateway.answer(image, includeExplanation, optionalOcrText)
                }
                AutomaticAnswerValidator.parseJson(json)
            }
            AnswerBackend.SIMULATED -> simulatedInterpreter.answerFromImage(image, includeExplanation, optionalOcrText)
        }
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

    override suspend fun answerFromImage(
        image: CapturedQuestionMedia,
        includeExplanation: Boolean,
        optionalOcrText: String?,
    ): AutomaticAnswer {
        delay(80)
        return AutomaticAnswer(
            status = AutomaticAnswerStatus.ANSWERED,
            questionText = "Simulated demo MCQ from the visible camera image.",
            options = listOf(
                InterpretedOption(0, "A", "Simulated distractor"),
                InterpretedOption(1, "B", "Simulated answer choice"),
            ),
            selectedOptionIndex = 1,
            answerLabel = "B",
            answerText = "Simulated answer choice",
            explanation = "Demo builds are network-free; this deterministic answer exercises the automatic loop without Firebase/Gemini.",
            confidence = 0.62,
            imageReadable = true,
            answerable = true,
            simulated = true,
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

object AutomaticAnswerValidator {
    private val gson = Gson()
    private const val MaxText = 1_200

    fun parseJson(json: String): AutomaticAnswer {
        val dto = try {
            gson.fromJson(json.take(64_000), AutomaticAnswerDto::class.java)
        } catch (e: JsonSyntaxException) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Malformed automatic answer JSON.")
        } ?: throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Blank automatic answer JSON.")
        val status = runCatching { AutomaticAnswerStatus.valueOf(dto.status.orEmpty()) }.getOrNull()
            ?: throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Unknown automatic answer status.")
        val confidence = dto.confidence
        if (!confidence.isFinite() || confidence !in 0.0..1.0) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Invalid automatic answer confidence.")
        }
        val imageReadable = dto.imageReadable ?: (status == AutomaticAnswerStatus.ANSWERED)
        val answerable = dto.answerable ?: (status == AutomaticAnswerStatus.ANSWERED)
        if (status != AutomaticAnswerStatus.ANSWERED) {
            if (status == AutomaticAnswerStatus.UNREADABLE || !imageReadable) {
                throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Image is unreadable.")
            }
            if (status == AutomaticAnswerStatus.NO_SINGLE_MCQ || !answerable) {
                throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "No identifiable single MCQ was found.")
            }
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Automatic answer is unsupported.")
        }
        if (!imageReadable) throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Image is unreadable.")
        if (!answerable) throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "No identifiable single MCQ was found.")
        val questionText = dto.questionText.clean()
            ?: dto.questionSummary.clean()
            ?: throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "ANSWERED result needs question text.")
        val options = dto.options.orEmpty().map {
            InterpretedOption(it.position, it.displayLabel.orEmpty().trim(), it.text.orEmpty().trim())
        }
        validateAnswerOptions(options)
        val selectedOptionIndex = dto.selectedOptionIndex
            ?: throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "ANSWERED result needs selectedOptionIndex.")
        if (selectedOptionIndex !in options.indices) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Selected option is outside returned options.")
        }
        val answerText = dto.answerText.clean()
            ?: options[selectedOptionIndex].text.takeIf(String::isNotBlank)
        if (answerText.isNullOrBlank()) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "ANSWERED result needs answerText.")
        }
        val explanation = dto.explanation.clean()
        if (explanation.isNullOrBlank()) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "ANSWERED result needs explanation.")
        }
        return AutomaticAnswer(
            status = status,
            questionText = questionText,
            options = options,
            selectedOptionIndex = selectedOptionIndex,
            answerLabel = dto.answerLabel.clean(32) ?: options[selectedOptionIndex].displayLabel.takeIf(String::isNotBlank),
            answerText = answerText,
            explanation = explanation,
            confidence = confidence,
            imageReadable = imageReadable,
            answerable = answerable,
        )
    }

    private fun validateAnswerOptions(options: List<InterpretedOption>) {
        if (options.size !in 2..8) throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "ANSWERED result needs 2-8 options.")
        val positions = options.map { it.position }
        if (positions != options.indices.toList()) throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Automatic answer option positions must be contiguous.")
        if (options.any { it.text.isBlank() }) throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Automatic answer option text is blank.")
        if (options.map { it.text.lowercase() }.distinct().size != options.size) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Automatic answer options must be distinct.")
        }
    }

    private fun String?.clean(limit: Int = MaxText): String? =
        this?.trim()?.take(limit)?.takeIf(String::isNotBlank)

    private data class AutomaticAnswerDto(
        val status: String?,
        val questionText: String?,
        val questionSummary: String?,
        val options: List<OptionDto>?,
        val selectedOptionIndex: Int?,
        val answerLabel: String?,
        val answerText: String?,
        val explanation: String?,
        val confidence: Double,
        val imageReadable: Boolean?,
        val answerable: Boolean?,
    )

    private data class OptionDto(
        val position: Int,
        val displayLabel: String?,
        val text: String?,
    )
}
