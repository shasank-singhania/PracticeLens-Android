package app.practicelens.android.evaluation

import app.practicelens.android.core.EvaluationResult
import app.practicelens.android.core.LockedAttempt
import app.practicelens.android.core.PracticeQuestion
import app.practicelens.android.interpretation.ModelResponseException
import app.practicelens.android.interpretation.PracticeLensError
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

interface GeminiEvaluator {
    suspend fun evaluate(question: PracticeQuestion, attempt: LockedAttempt): EvaluationResult
}

interface EvaluationGateway {
    suspend fun evaluate(request: EvaluationGatewayRequest): String
}

data class EvaluationGatewayRequest(
    val imageUri: String,
    val imageMimeType: String,
    val imageSha256: String,
    val questionText: String,
    val options: List<EvaluationOption>,
    val selectedOptionId: String,
    val optionalOcrText: String?,
)

data class EvaluationOption(val id: String, val text: String)

class FakePracticeEvaluator : GeminiEvaluator {
    override suspend fun evaluate(question: PracticeQuestion, attempt: LockedAttempt): EvaluationResult {
        delay(150)
        val correct = question.options.getOrNull(1)?.id ?: question.options.first().id
        return EvaluationResult(
            correctOptionId = correct,
            explanation = "Fake demo test data: compare the chosen option against the key idea in the prompt before committing.",
            confidence = 0.72,
            uncertain = false,
            warning = "Fake evaluator; no network request was made.",
        )
    }
}

class GatewayGeminiEvaluator(
    private val gateway: EvaluationGateway,
    private val timeoutMs: Long = 20_000,
) : GeminiEvaluator {
    override suspend fun evaluate(question: PracticeQuestion, attempt: LockedAttempt): EvaluationResult {
        val media = question.media ?: throw ModelResponseException(PracticeLensError.IMAGE_UNAVAILABLE, "Confirmed crop is unavailable.")
        val request = EvaluationGatewayRequest(
            imageUri = media.uri,
            imageMimeType = media.mimeType,
            imageSha256 = media.sha256,
            questionText = question.prompt,
            options = question.options.map { EvaluationOption(it.id, it.text) },
            selectedOptionId = attempt.selectedOptionId,
            optionalOcrText = question.ocrText,
        )
        return evaluateWithSingleTransientRetry(request, question.options.map { it.id }.toSet())
    }

    private suspend fun evaluateWithSingleTransientRetry(request: EvaluationGatewayRequest, validIds: Set<String>): EvaluationResult {
        var transientFailure: ModelResponseException? = null
        repeat(2) { attempt ->
            try {
                val json = withTimeout(timeoutMs) { gateway.evaluate(request) }
                return EvaluationResponseValidator.parseJson(json, validIds)
            } catch (e: TimeoutCancellationException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelResponseException) {
                if (e.error != PracticeLensError.TEMPORARY_SERVICE_FAILURE || attempt == 1) throw e
                transientFailure = e
            }
        }
        throw transientFailure ?: ModelResponseException(PracticeLensError.UNKNOWN, "Evaluation failed.")
    }
}

object EvaluationResponseValidator {
    private val gson = Gson()

    fun parseJson(json: String, validOptionIds: Set<String>): EvaluationResult {
        val dto = try {
            gson.fromJson(json.take(64_000), EvaluationDto::class.java)
        } catch (e: JsonSyntaxException) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Malformed evaluation JSON.")
        } ?: throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Blank evaluation JSON.")
        val confidence = dto.confidence
        if (!confidence.isFinite() || confidence !in 0.0..1.0) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Invalid evaluation confidence.")
        }
        val correctId = dto.correctOptionId.orEmpty().trim()
        if (correctId !in validOptionIds) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Model returned an unknown option ID.")
        }
        val explanation = dto.explanation.orEmpty().trim()
        if (explanation.isBlank()) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Evaluation explanation is blank.")
        }
        if (dto.uncertain && dto.warning.isNullOrBlank() && confidence > 0.5) {
            throw ModelResponseException(PracticeLensError.INVALID_MODEL_RESPONSE, "Uncertain evaluation needs a bounded confidence or warning.")
        }
        return EvaluationResult(
            correctOptionId = correctId,
            explanation = explanation.take(1200),
            confidence = confidence,
            uncertain = dto.uncertain,
            warning = dto.warning?.trim()?.takeIf(String::isNotBlank),
        )
    }

    private data class EvaluationDto(
        val correctOptionId: String?,
        val explanation: String?,
        val confidence: Double,
        val uncertain: Boolean,
        val warning: String?,
    )
}
