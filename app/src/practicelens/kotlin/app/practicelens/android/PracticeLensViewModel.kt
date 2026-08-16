package app.practicelens.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.practicelens.android.core.EvaluationState
import app.practicelens.android.core.MonotonicClock
import app.practicelens.android.core.PracticeAttemptState
import app.practicelens.android.core.PracticeReducer
import app.practicelens.android.core.SystemMonotonicClock
import app.practicelens.android.evaluation.EvaluatorFactory
import app.practicelens.android.ocr.OcrDraft
import app.practicelens.android.ocr.OcrObservation
import app.practicelens.android.ocr.OcrOptionDraft
import app.practicelens.android.ocr.OcrParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PracticeLensUiState(
    val disclosureAccepted: Boolean = false,
    val cameraPermissionGranted: Boolean = false,
    val cameraPermissionPermanentlyDenied: Boolean = false,
    val scanning: Boolean = false,
    val scannerError: String? = null,
    val ocrReview: OcrDraft? = null,
    val attempt: PracticeAttemptState? = null,
    val feedbackMode: String = "Feedback after each answer",
    val autoNextSeconds: Int = 5,
    val autoNextRemainingSeconds: Int = 0,
    val autoNextProgress: Float = 0f,
)

class PracticeLensViewModel(
    private val clock: MonotonicClock = SystemMonotonicClock,
) : ViewModel() {
    private val reducer = PracticeReducer(clock)
    private val evaluator = EvaluatorFactory.create()
    private val parser = OcrParser()
    private var graceJob: Job? = null
    private var autoNextJob: Job? = null
    private val _uiState = MutableStateFlow(PracticeLensUiState())
    val uiState: StateFlow<PracticeLensUiState> = _uiState

    fun acceptDisclosure() {
        _uiState.update { it.copy(disclosureAccepted = true) }
    }

    fun setCameraPermission(granted: Boolean, permanentlyDenied: Boolean = false) {
        _uiState.update {
            it.copy(
                cameraPermissionGranted = granted,
                cameraPermissionPermanentlyDenied = !granted && permanentlyDenied,
                scanning = granted && it.disclosureAccepted && it.attempt == null && it.ocrReview == null,
            )
        }
    }

    fun resumeScanning() {
        graceJob?.cancel()
        autoNextJob?.cancel()
        _uiState.update {
            it.copy(
                scanning = it.cameraPermissionGranted && it.disclosureAccepted,
                scannerError = null,
                ocrReview = null,
                attempt = null,
                autoNextRemainingSeconds = 0,
                autoNextProgress = 0f,
            )
        }
    }

    fun acceptOcrText(text: String) {
        openOcrReview(OcrObservation(text))
    }

    fun openOcrReview(observation: OcrObservation) {
        val draft = parser.parse(observation)
        _uiState.update {
            it.copy(
                scanning = false,
                scannerError = null,
                ocrReview = draft,
                attempt = null,
            )
        }
    }

    fun scannerFailed(message: String) {
        _uiState.update { it.copy(scanning = false, scannerError = message) }
    }

    fun editOcrQuestion(question: String) {
        updateOcrDraft { parser.validate(question, it.options, it.rawText) }
    }

    fun editOcrOptionLabel(index: Int, label: String) {
        updateOcrDraft {
            val options = it.options.toMutableList()
            if (index !in options.indices) return@updateOcrDraft it
            options[index] = options[index].copy(label = label)
            parser.validate(it.question, options, it.rawText)
        }
    }

    fun editOcrOptionText(index: Int, text: String) {
        updateOcrDraft {
            val options = it.options.toMutableList()
            if (index !in options.indices) return@updateOcrDraft it
            options[index] = options[index].copy(text = text)
            parser.validate(it.question, options, it.rawText)
        }
    }

    fun addOcrOption() {
        updateOcrDraft {
            if (it.options.size >= 8) return@updateOcrDraft it
            val used = it.options.map { option -> option.label }.toSet()
            val nextLabel = ('A'..'H').map(Char::toString).firstOrNull { label -> label !in used } ?: ""
            parser.validate(it.question, it.options + OcrOptionDraft(nextLabel, ""), it.rawText)
        }
    }

    fun removeOcrOption(index: Int) {
        updateOcrDraft {
            if (it.options.size <= 2 || index !in it.options.indices) return@updateOcrDraft it
            parser.validate(it.question, it.options.filterIndexed { i, _ -> i != index }, it.rawText)
        }
    }

    fun rejectOcr() {
        _uiState.update { it.copy(scanning = false, ocrReview = null) }
    }

    fun confirmOcrDraft() {
        val draft = _uiState.value.ocrReview ?: return
        if (!draft.valid) return
        _uiState.update {
            it.copy(
                scanning = false,
                ocrReview = null,
                attempt = PracticeAttemptState(question = draft.toPracticeQuestion(), createdAtMs = clock.nowMs()),
            )
        }
    }

    private fun updateOcrDraft(transform: (OcrDraft) -> OcrDraft) {
        _uiState.update { state ->
            val draft = state.ocrReview ?: return@update state
            state.copy(ocrReview = transform(draft))
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
