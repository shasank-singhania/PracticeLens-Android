package app.practicelens.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.practicelens.android.core.EvaluationState
import app.practicelens.android.core.PracticeAttemptState
import app.practicelens.android.core.PracticeOption
import app.practicelens.android.core.PracticeQuestion
import app.practicelens.android.core.PracticeReducer
import app.practicelens.android.core.SystemMonotonicClock
import app.practicelens.android.evaluation.EvaluatorFactory
import app.practicelens.android.ocr.OcrParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PracticeLensUiState(
    val cameraPermissionGranted: Boolean = false,
    val scanning: Boolean = false,
    val attempt: PracticeAttemptState? = null,
    val feedbackMode: String = "Feedback after each answer",
    val autoNextSeconds: Int = 5,
    val autoNextRemainingSeconds: Int = 0,
    val autoNextProgress: Float = 0f,
)

class PracticeLensViewModel : ViewModel() {
    private val reducer = PracticeReducer(SystemMonotonicClock)
    private val evaluator = EvaluatorFactory.create()
    private val parser = OcrParser()
    private var graceJob: Job? = null
    private var autoNextJob: Job? = null
    private val _uiState = MutableStateFlow(PracticeLensUiState())
    val uiState: StateFlow<PracticeLensUiState> = _uiState

    fun setCameraPermission(granted: Boolean) {
        _uiState.update { it.copy(cameraPermissionGranted = granted, scanning = granted && it.attempt == null) }
    }

    fun resumeScanning() {
        graceJob?.cancel()
        autoNextJob?.cancel()
        _uiState.update { it.copy(scanning = true, attempt = null, autoNextRemainingSeconds = 0, autoNextProgress = 0f) }
    }

    fun acceptOcrText(text: String) {
        val parsed = parser.parse(text)
        val question = parsed.question ?: PracticeQuestion(
            prompt = text,
            options = listOf(PracticeOption("A", "Edit option"), PracticeOption("B", "Edit option")),
        )
        _uiState.update {
            it.copy(
                scanning = false,
                attempt = PracticeAttemptState(question = question, createdAtMs = SystemMonotonicClock.nowMs()),
            )
        }
    }

    fun editPrompt(prompt: String) {
        _uiState.update { state ->
            val attempt = state.attempt ?: return@update state
            state.copy(attempt = attempt.copy(question = attempt.question.copy(prompt = prompt)))
        }
    }

    fun select(optionId: String) {
        graceJob?.cancel()
        autoNextJob?.cancel()
        var snapshot: PracticeAttemptState? = null
        _uiState.update { state ->
            val attempt = state.attempt ?: return@update state
            val next = reducer.selectOption(attempt, optionId)
            snapshot = next
            state.copy(attempt = next)
        }
        val selected = snapshot ?: return
        graceJob = viewModelScope.launch {
            delay(700)
            lockAndEvaluate(selected.question.id, selected.selectionVersion, optionId)
        }
    }

    private fun lockAndEvaluate(questionId: String, selectionVersion: Long, optionId: String) {
        var locked: PracticeAttemptState? = null
        _uiState.update { state ->
            val attempt = state.attempt ?: return@update state
            val next = reducer.tryLockForEvaluation(attempt, questionId, selectionVersion, optionId)
            locked = next.takeIf { it.evaluationState == EvaluationState.IN_FLIGHT }
            state.copy(attempt = next)
        }
        val inFlight = locked ?: return
        viewModelScope.launch {
            val result = runCatching { evaluator.evaluate(inFlight.question, inFlight.lockedAttempt!!) }
            _uiState.update { state ->
                val attempt = state.attempt ?: return@update state
                val next = result.fold(
                    onSuccess = { reducer.complete(attempt, it) },
                    onFailure = { reducer.fail(attempt) },
                )
                state.copy(attempt = next)
            }
            if (result.isSuccess) startAutoNext()
        }
    }

    private fun startAutoNext() {
        val seconds = _uiState.value.autoNextSeconds
        if (seconds <= 0) return
        autoNextJob?.cancel()
        autoNextJob = viewModelScope.launch {
            for (remaining in seconds downTo 0) {
                _uiState.update {
                    it.copy(
                        autoNextRemainingSeconds = remaining,
                        autoNextProgress = 1f - (remaining.toFloat() / seconds.toFloat()),
                    )
                }
                delay(1000)
            }
            nextNow()
        }
    }

    fun stopAutoNext() {
        autoNextJob?.cancel()
        _uiState.update { it.copy(autoNextRemainingSeconds = 0, autoNextProgress = 0f) }
    }

    fun nextNow() = resumeScanning()

    fun practiceAgainLater() {
        graceJob?.cancel()
        stopAutoNext()
        _uiState.update { it.copy(scanning = false) }
    }

    fun retryEvaluation() {
        val attempt = _uiState.value.attempt ?: return
        val selected = attempt.finalSelectedOptionId ?: return
        _uiState.update { it.copy(attempt = reducer.retry(attempt)) }
        select(selected)
    }

    fun retake() = resumeScanning()

    fun onBackgrounded() {
        graceJob?.cancel()
        stopAutoNext()
        _uiState.update { it.copy(scanning = false) }
    }
}
