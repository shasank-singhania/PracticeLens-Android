package app.practicelens.android

import app.practicelens.android.core.EvaluationResult
import app.practicelens.android.core.EvaluationState
import app.practicelens.android.core.LockedAttempt
import app.practicelens.android.core.MonotonicClock
import app.practicelens.android.core.PracticeQuestion
import app.practicelens.android.evaluation.GeminiEvaluator
import app.practicelens.android.interpretation.PracticeLensError
import app.practicelens.android.ocr.OcrObservation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PracticeLensViewModelTest {
    @Test fun `permission alone does not bypass pre session settings`() {
        val vm = PracticeLensViewModel(FakeClock)

        vm.setCameraPermission(true)
        assertFalse(vm.uiState.value.scanning)
        assertEquals(AutomaticPracticeState.CONFIGURING, vm.uiState.value.automaticState)

        vm.resumeScanning()
        assertTrue(vm.uiState.value.scanning)
    }

    @Test fun `denial can be retried or marked permanently denied`() {
        val vm = PracticeLensViewModel(FakeClock)

        vm.acceptDisclosure()
        vm.setCameraPermission(false, permanentlyDenied = true)

        assertFalse(vm.uiState.value.cameraPermissionGranted)
        assertTrue(vm.uiState.value.cameraPermissionPermanentlyDenied)
    }

    @Test fun `ambiguous draft cannot continue`() {
        val vm = PracticeLensViewModel(FakeClock)

        vm.openOcrReview(OcrObservation("Question\nA. One"))
        vm.confirmOcrDraft()

        assertFalse(vm.uiState.value.ocrReview?.valid ?: true)
        assertNull(vm.uiState.value.attempt)
    }

    @Test fun `question and option editing can create a valid attempt exactly once`() {
        val vm = PracticeLensViewModel(FakeClock)

        vm.openOcrReview(OcrObservation("Question\nA. One"))
        vm.addOcrOption()
        vm.editOcrOptionText(0, "One")
        vm.addOcrOption()
        vm.editOcrOptionText(1, "Two")
        vm.editOcrQuestion("Corrected question")
        vm.confirmOcrDraft()
        vm.confirmOcrDraft()

        val attempt = vm.uiState.value.attempt
        assertEquals("Corrected question", attempt?.question?.prompt)
        assertEquals(2, attempt?.question?.options?.size)
        assertNull(vm.uiState.value.ocrReview)
    }

    @Test fun `add remove limits and retake resumes scanning`() {
        val vm = PracticeLensViewModel(FakeClock)
        vm.acceptDisclosure()
        vm.setCameraPermission(true)
        vm.openOcrReview(OcrObservation("Question\nA. One\nB. Two"))

        vm.removeOcrOption(0)
        assertEquals(2, vm.uiState.value.ocrReview?.options?.size)

        repeat(10) { vm.addOcrOption() }
        assertEquals(8, vm.uiState.value.ocrReview?.options?.size)

        vm.retake()
        assertTrue(vm.uiState.value.scanning)
        assertNull(vm.uiState.value.ocrReview)
    }

    @Test fun `backgrounding during evaluation keeps attempt retryable without unknown failure`() = viewModelRunTest {
        val evaluator = DeferredEvaluator()
        val vm = PracticeLensViewModel(FakeClock, evaluator = evaluator)

        startEvaluation(vm)
        advanceTimeBy(700)
        runCurrent()
        assertEquals(EvaluationState.IN_FLIGHT, vm.uiState.value.attempt?.evaluationState)

        vm.onBackgrounded()
        runCurrent()

        assertEquals(EvaluationState.GRACE_PERIOD, vm.uiState.value.attempt?.evaluationState)
        assertEquals(PracticeLensError.CANCELLED, vm.uiState.value.evaluationError)
        assertFalse(vm.uiState.value.evaluationError == PracticeLensError.UNKNOWN)
        assertEquals(0, vm.uiState.value.autoNextRemainingSeconds)
    }

    @Test fun `retake and new scan prevent cancelled response from updating replacement state`() = viewModelRunTest {
        val evaluator = DeferredEvaluator()
        val vm = PracticeLensViewModel(FakeClock, evaluator = evaluator)

        startEvaluation(vm)
        advanceTimeBy(700)
        runCurrent()
        val oldResponse = evaluator.requests.single()
        vm.retake()
        vm.openOcrReview(OcrObservation("Replacement?\nA. Red\nB. Blue"))
        vm.confirmOcrDraft()
        oldResponse.complete(successResult())
        runCurrent()

        assertEquals("Replacement?", vm.uiState.value.attempt?.question?.prompt)
        assertEquals(EvaluationState.NOT_STARTED, vm.uiState.value.attempt?.evaluationState)
        assertNull(vm.uiState.value.attempt?.result)
    }

    @Test fun `timeout produces timeout error instead of cancelled or unknown`() = viewModelRunTest {
        val vm = PracticeLensViewModel(FakeClock, evaluator = TimeoutEvaluator)

        startEvaluation(vm)
        advanceTimeBy(700)
        runCurrent()
        advanceTimeBy(1)
        runCurrent()

        assertEquals(EvaluationState.FAILED, vm.uiState.value.attempt?.evaluationState)
        assertEquals(PracticeLensError.TIMEOUT, vm.uiState.value.evaluationError)
    }

    @Test fun `retry remains possible after background cancellation`() = viewModelRunTest {
        val evaluator = DeferredEvaluator()
        val vm = PracticeLensViewModel(FakeClock, evaluator = evaluator)

        startEvaluation(vm)
        advanceTimeBy(700)
        runCurrent()
        vm.onBackgrounded()
        runCurrent()
        vm.retryEvaluation()
        advanceTimeBy(700)
        runCurrent()
        evaluator.requests.last().complete(successResult())
        runCurrent()

        assertEquals(EvaluationState.COMPLETE, vm.uiState.value.attempt?.evaluationState)
        assertEquals("option-1", vm.uiState.value.attempt?.result?.correctOptionId)
        assertNull(vm.uiState.value.evaluationError)
    }

    @Test fun `successful evaluation still completes and starts auto next`() = viewModelRunTest {
        val evaluator = DeferredEvaluator()
        val vm = PracticeLensViewModel(FakeClock, evaluator = evaluator)

        startEvaluation(vm)
        advanceTimeBy(700)
        runCurrent()
        evaluator.requests.single().complete(successResult())
        runCurrent()

        assertEquals(EvaluationState.COMPLETE, vm.uiState.value.attempt?.evaluationState)
        assertEquals("option-1", vm.uiState.value.attempt?.result?.correctOptionId)
        assertNull(vm.uiState.value.evaluationError)
        assertTrue(vm.uiState.value.autoNextRemainingSeconds > 0)
    }

    private fun startEvaluation(vm: PracticeLensViewModel) {
        vm.openOcrReview(OcrObservation("Question?\nA. One\nB. Two"))
        vm.confirmOcrDraft()
        vm.select("option-0")
    }

    private object FakeClock : MonotonicClock {
        override fun nowMs(): Long = 42
    }

    private fun viewModelRunTest(block: suspend TestScope.() -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher, testBody = block)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class DeferredEvaluator : GeminiEvaluator {
        val requests = mutableListOf<CompletableDeferred<EvaluationResult>>()

        override suspend fun evaluate(question: PracticeQuestion, attempt: LockedAttempt): EvaluationResult {
            val response = CompletableDeferred<EvaluationResult>()
            requests += response
            return response.await()
        }
    }

    private object TimeoutEvaluator : GeminiEvaluator {
        override suspend fun evaluate(question: PracticeQuestion, attempt: LockedAttempt): EvaluationResult =
            withTimeout(1) { awaitCancellation() }
    }

    private fun successResult() = EvaluationResult(
        correctOptionId = "option-1",
        explanation = "Two is correct.",
        confidence = 0.9,
        uncertain = false,
    )
}
