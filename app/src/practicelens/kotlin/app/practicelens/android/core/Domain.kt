package app.practicelens.android.core

import java.security.MessageDigest
import java.util.UUID

enum class FeedbackMode { AFTER_EACH_ANSWER, SESSION_REVIEW }
enum class EvaluationState { NOT_STARTED, GRACE_PERIOD, IN_FLIGHT, COMPLETE, FAILED }

data class PracticeOption(val id: String, val text: String)

data class PracticeQuestion(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String,
    val options: List<PracticeOption>,
    val sourceFingerprint: String = fingerprint(prompt + options.joinToString { it.id + it.text }),
)

data class LockedAttempt(
    val questionId: String,
    val selectedOptionId: String,
    val selectionVersion: Long,
    val lockedAtMs: Long,
)

data class EvaluationResult(
    val correctOptionId: String,
    val explanation: String,
    val confidence: Double,
    val uncertain: Boolean,
    val warning: String? = null,
)

data class PracticeAttemptState(
    val question: PracticeQuestion,
    val firstSelectedOptionId: String? = null,
    val finalSelectedOptionId: String? = null,
    val answerChangeCount: Int = 0,
    val selectionVersion: Long = 0,
    val evaluationState: EvaluationState = EvaluationState.NOT_STARTED,
    val createdAtMs: Long,
    val firstAnswerAtMs: Long? = null,
    val finalLockAtMs: Long? = null,
    val lockedAttempt: LockedAttempt? = null,
    val result: EvaluationResult? = null,
    val retryCount: Int = 0,
    val questionableFeedback: Boolean = false,
)

class PracticeReducer(private val clock: MonotonicClock) {
    fun selectOption(state: PracticeAttemptState, optionId: String): PracticeAttemptState {
        require(state.question.options.any { it.id == optionId }) { "Unknown option $optionId" }
        require(state.evaluationState != EvaluationState.IN_FLIGHT && state.evaluationState != EvaluationState.COMPLETE) {
            "Attempt is already locked"
        }
        val now = clock.nowMs()
        val isFirst = state.firstSelectedOptionId == null
        val changed = state.finalSelectedOptionId != null && state.finalSelectedOptionId != optionId
        return state.copy(
            firstSelectedOptionId = state.firstSelectedOptionId ?: optionId,
            finalSelectedOptionId = optionId,
            answerChangeCount = state.answerChangeCount + if (changed) 1 else 0,
            selectionVersion = state.selectionVersion + 1,
            evaluationState = EvaluationState.GRACE_PERIOD,
            firstAnswerAtMs = state.firstAnswerAtMs ?: now,
        )
    }

    fun tryLockForEvaluation(
        state: PracticeAttemptState,
        questionId: String,
        selectionVersion: Long,
        selectedOptionId: String,
    ): PracticeAttemptState {
        val currentSelection = state.finalSelectedOptionId
        if (
            state.question.id != questionId ||
            state.selectionVersion != selectionVersion ||
            currentSelection != selectedOptionId ||
            state.evaluationState != EvaluationState.GRACE_PERIOD
        ) return state
        val now = clock.nowMs()
        return state.copy(
            evaluationState = EvaluationState.IN_FLIGHT,
            finalLockAtMs = now,
            lockedAttempt = LockedAttempt(questionId, selectedOptionId, selectionVersion, now),
        )
    }

    fun complete(state: PracticeAttemptState, result: EvaluationResult): PracticeAttemptState {
        val locked = state.lockedAttempt ?: return state
        require(result.correctOptionId in state.question.options.map { it.id }) { "Correct option is not present" }
        require(result.explanation.isNotBlank()) { "Explanation is required" }
        require(result.confidence in 0.0..1.0) { "Invalid confidence" }
        return state.copy(
            evaluationState = EvaluationState.COMPLETE,
            finalSelectedOptionId = locked.selectedOptionId,
            result = result,
        )
    }

    fun fail(state: PracticeAttemptState): PracticeAttemptState =
        state.copy(evaluationState = EvaluationState.FAILED)

    fun markQuestionable(state: PracticeAttemptState): PracticeAttemptState =
        state.copy(questionableFeedback = true)

    fun retry(state: PracticeAttemptState): PracticeAttemptState =
        state.copy(evaluationState = EvaluationState.GRACE_PERIOD, retryCount = state.retryCount + 1)
}

interface MonotonicClock { fun nowMs(): Long }

object SystemMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
}

fun fingerprint(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.trim().lowercase().toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
