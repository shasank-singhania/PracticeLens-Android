package app.practicelens.android

import app.practicelens.android.camera.QuestionImagePreparationCore
import app.practicelens.android.core.CapturedQuestionMedia
import app.practicelens.android.core.MonotonicClock
import app.practicelens.android.interpretation.AutomaticAnswer
import app.practicelens.android.interpretation.AutomaticAnswerStatus
import app.practicelens.android.interpretation.AutomaticAnswerValidator
import app.practicelens.android.interpretation.InterpretationStatus
import app.practicelens.android.interpretation.ModelResponseException
import app.practicelens.android.interpretation.QuestionImageInterpreter
import app.practicelens.android.interpretation.QuestionInterpretation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticPracticeWorkflowTest {
    @Test fun `initial automatic state is configuring`() {
        val vm = PracticeLensViewModel(TestClock, RecordingInterpreter())

        assertEquals(AutomaticPracticeState.CONFIGURING, vm.uiState.value.automaticState)
        assertFalse(vm.uiState.value.scanning)
        assertEquals(0, vm.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `automatic mode is default`() {
        val vm = PracticeLensViewModel(TestClock, RecordingInterpreter())

        assertEquals(PracticeMode.AUTOMATIC_AI_PRACTICE, vm.uiState.value.practiceMode)
        assertEquals(5, vm.uiState.value.resultDisplaySeconds)
        assertTrue(vm.uiState.value.includeExplanation)
        assertTrue(vm.uiState.value.automaticallyContinue)
    }

    @Test fun `camera and capture do not start before start`() = viewModelRunTest {
        val vm = readyVm()

        vm.automaticCameraReady()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(AutomaticPracticeState.CONFIGURING, vm.uiState.value.automaticState)
        assertFalse(vm.uiState.value.scanning)
        assertEquals(0, vm.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `start creates one automatic capture request after readiness and focus delay`() = viewModelRunTest {
        val vm = readyVm()

        vm.startAutomaticPractice()
        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        vm.automaticCameraReady()

        assertEquals(AutomaticPracticeState.WAITING_FOR_FOCUS, vm.uiState.value.automaticState)
        assertEquals(0, vm.uiState.value.automaticCaptureRequestId)

        advanceTimeBy(700)
        runCurrent()

        assertEquals(AutomaticPracticeState.CAPTURING, vm.uiState.value.automaticState)
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `recomposition style repeated readiness events do not duplicate capture`() = viewModelRunTest {
        val vm = readyVm()

        vm.startAutomaticPractice()
        repeat(8) { vm.automaticCameraReady() }
        advanceTimeBy(700)
        runCurrent()
        repeat(8) { vm.automaticCameraReady() }
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(AutomaticPracticeState.CAPTURING, vm.uiState.value.automaticState)
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `valid answer shows result then waits for scene change`() = viewModelRunTest {
        val interpreter = RecordingInterpreter()
        val vm = readyVm(interpreter)
        vm.setResultDisplaySeconds(3)
        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        advanceTimeBy(700)
        runCurrent()

        vm.onAutomaticImageCaptured(media("one", "0000000000000000"))
        runCurrent()

        assertEquals(AutomaticPracticeState.SHOWING_RESULT, vm.uiState.value.automaticState)
        assertEquals(1, interpreter.answerCalls)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(AutomaticPracticeState.WAITING_FOR_SCENE_CHANGE, vm.uiState.value.automaticState)
    }

    @Test fun `duplicate perceptual hash suppresses next model call`() = viewModelRunTest {
        val interpreter = RecordingInterpreter()
        val vm = readyVm(interpreter)
        vm.setResultDisplaySeconds(3)
        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        advanceTimeBy(700)
        runCurrent()
        vm.onAutomaticImageCaptured(media("one", "0000000000000000"))
        runCurrent()
        advanceTimeBy(4_500)
        runCurrent()

        vm.onAutomaticImageCaptured(media("dup", "0000000000000001"))
        runCurrent()

        assertEquals(1, interpreter.answerCalls)
        assertEquals(AutomaticPracticeState.WAITING_FOR_SCENE_CHANGE, vm.uiState.value.automaticState)
    }

    @Test fun `two materially changed observations permit next request`() = viewModelRunTest {
        val interpreter = RecordingInterpreter()
        val vm = readyVm(interpreter)
        vm.setResultDisplaySeconds(3)
        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        advanceTimeBy(700)
        runCurrent()
        vm.onAutomaticImageCaptured(media("one", "0000000000000000"))
        runCurrent()
        advanceTimeBy(4_500)
        runCurrent()

        vm.onAutomaticImageCaptured(media("changed-a", "ffffffffffffffff"))
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        vm.onAutomaticImageCaptured(media("changed-b", "ffffffffffffffff"))
        advanceTimeBy(8_000)
        runCurrent()

        assertEquals(2, interpreter.answerCalls)
    }

    @Test fun `result delay and scene change automatically request next captures`() = viewModelRunTest {
        val interpreter = RecordingInterpreter()
        val vm = readyVm(interpreter)
        vm.setResultDisplaySeconds(3)
        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        advanceTimeBy(700)
        runCurrent()

        vm.onAutomaticImageCaptured(media("one", "0000000000000000"))
        runCurrent()
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(AutomaticPracticeState.WAITING_FOR_SCENE_CHANGE, vm.uiState.value.automaticState)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(AutomaticPracticeState.CAPTURING, vm.uiState.value.automaticState)
        assertEquals(2, vm.uiState.value.automaticCaptureRequestId)

        vm.onAutomaticImageCaptured(media("changed-a", "ffffffffffffffff"))
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(3, vm.uiState.value.automaticCaptureRequestId)
        vm.onAutomaticImageCaptured(media("changed-b", "ffffffffffffffff"))
        advanceTimeBy(8_000)
        runCurrent()

        assertEquals(2, interpreter.answerCalls)
    }

    @Test fun `request ceiling stops session`() = viewModelRunTest {
        val interpreter = RecordingInterpreter()
        val vm = readyVm(interpreter)
        vm.setAutomaticRequestCeiling(1)
        vm.setResultDisplaySeconds(3)
        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        advanceTimeBy(700)
        runCurrent()
        vm.onAutomaticImageCaptured(media("one", "0000000000000000"))
        runCurrent()
        advanceTimeBy(4_500)
        runCurrent()
        vm.onAutomaticImageCaptured(media("changed-a", "ffffffffffffffff"))
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        vm.onAutomaticImageCaptured(media("changed-b", "ffffffffffffffff"))
        runCurrent()

        assertEquals(AutomaticPracticeState.CONFIGURING, vm.uiState.value.automaticState)
        assertTrue(vm.uiState.value.automaticStatus.contains("limit"))
        assertEquals(1, interpreter.answerCalls)
    }

    @Test fun `pause resume stop and backgrounding cancel loop state`() = viewModelRunTest {
        val vm = readyVm()
        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        vm.pauseAutomaticPractice()
        assertEquals(AutomaticPracticeState.PAUSED, vm.uiState.value.automaticState)

        vm.resumeAutomaticPractice()
        assertEquals(AutomaticPracticeState.CAMERA_STARTING, vm.uiState.value.automaticState)

        vm.stopAutomaticPractice()
        assertEquals(AutomaticPracticeState.CONFIGURING, vm.uiState.value.automaticState)

        vm.startAutomaticPractice()
        vm.onBackgrounded()
        assertEquals(AutomaticPracticeState.CONFIGURING, vm.uiState.value.automaticState)
    }

    @Test fun `stop returns to configuring and cancels pending focus capture`() = viewModelRunTest {
        val vm = readyVm()

        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        assertEquals(AutomaticPracticeState.WAITING_FOR_FOCUS, vm.uiState.value.automaticState)

        vm.stopAutomaticPractice()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(AutomaticPracticeState.CONFIGURING, vm.uiState.value.automaticState)
        assertFalse(vm.uiState.value.scanning)
        assertEquals(0, vm.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `settings layout contract keeps all options scroll reachable`() {
        val contract = AutomaticPracticeSettingsLayoutContract

        assertEquals(
            listOf(
                contract.modeLabel,
                contract.resultDurationLabel,
                contract.orientationLabel,
                contract.explanationLabel,
                contract.automaticallyContinueLabel,
                contract.requestLimitLabel,
            ),
            contract.requiredChoices,
        )
        assertTrue(contract.usesSingleVerticalScroll)
        assertTrue(contract.hasVisibleOverflowScrollThumb)
        assertTrue(contract.respectsSafeDrawingInsets)
        assertTrue(contract.contentCanOverflow(viewportHeightDp = 568))
        assertTrue(contract.allSettingsReachable(viewportHeightDp = 568))
        assertTrue(contract.contentCanOverflow(viewportHeightDp = 320))
        assertTrue(contract.allSettingsReachable(viewportHeightDp = 320))
        assertEquals("Start automatic practice", contract.startAutomaticLabel)
    }

    @Test fun `manual modes remain selectable without altering default`() {
        val vm = PracticeLensViewModel(TestClock, RecordingInterpreter())

        vm.setPracticeMode(PracticeMode.MANUAL_CAPTURE)
        assertEquals(PracticeMode.MANUAL_CAPTURE, vm.uiState.value.practiceMode)
        vm.setPracticeMode(PracticeMode.MANUAL_CROP_REVIEW)
        assertEquals(PracticeMode.MANUAL_CROP_REVIEW, vm.uiState.value.practiceMode)
    }

    @Test fun `automatic answer validator rejects malformed unsupported values`() {
        val parsed = AutomaticAnswerValidator.parseJson(
            """{"status":"ANSWERED","questionSummary":"Q","answerLabel":"A","answerText":"One","explanation":"Because.","confidence":0.7}""",
        )
        assertEquals(AutomaticAnswerStatus.ANSWERED, parsed.status)

        assertTrue(runCatching { AutomaticAnswerValidator.parseJson("{") }.exceptionOrNull() is ModelResponseException)
        assertTrue(
            runCatching {
                AutomaticAnswerValidator.parseJson("""{"status":"ANSWERED","answerText":"","confidence":0.5}""")
            }.exceptionOrNull() is ModelResponseException,
        )
    }

    @Test fun `orientation math and hash distance are deterministic`() {
        assertEquals(900 to 1200, QuestionImagePreparationCore.rotatedDimensions(1200, 900, 90))
        assertEquals(1200 to 900, QuestionImagePreparationCore.rotatedDimensions(1200, 900, 180))
        assertEquals(0, QuestionImagePreparationCore.hammingDistance("0000000000000000", "0000000000000000"))
        assertEquals(64, QuestionImagePreparationCore.hammingDistance("0000000000000000", "ffffffffffffffff"))
    }

    private fun readyVm(interpreter: RecordingInterpreter = RecordingInterpreter()) =
        PracticeLensViewModel(TestClock, interpreter).also {
            it.acceptDisclosure()
            it.setCameraPermission(true)
        }

    private fun media(sha: String, dhash: String) = CapturedQuestionMedia(
        uri = "file:///tmp/$sha.jpg",
        mimeType = "image/jpeg",
        width = 1200,
        height = 900,
        appliedRotationDegrees = 0,
        sha256 = sha,
        capturedAtMs = 1,
        qualityWarnings = listOf("dhash:$dhash"),
    )

    private object TestClock : MonotonicClock {
        override fun nowMs(): Long = 10_000
    }

    private class RecordingInterpreter : QuestionImageInterpreter {
        var answerCalls = 0

        override suspend fun interpret(image: CapturedQuestionMedia, optionalOcrText: String?): QuestionInterpretation =
            QuestionInterpretation(InterpretationStatus.RETAKE_REQUIRED, "", emptyList(), 0.0)

        override suspend fun answerFromImage(image: CapturedQuestionMedia, includeExplanation: Boolean): AutomaticAnswer {
            answerCalls++
            return AutomaticAnswer(
                status = AutomaticAnswerStatus.ANSWERED,
                questionSummary = "Question?",
                answerLabel = "A",
                answerText = "Answer",
                explanation = if (includeExplanation) "Short explanation." else null,
                confidence = 0.8,
            )
        }
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
}
