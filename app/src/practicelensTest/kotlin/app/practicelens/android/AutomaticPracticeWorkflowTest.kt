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
        assertFalse(vm.uiState.value.disclosureAccepted)
        assertFalse(vm.uiState.value.scanning)
        assertEquals(0, vm.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `disclosure acceptance persists and can be reset deterministically`() {
        val store = RecordingDisclosureStore(false)
        val vm = PracticeLensViewModel(TestClock, RecordingInterpreter(), disclosureStore = store)

        assertFalse(vm.uiState.value.disclosureAccepted)
        vm.acceptDisclosure()
        assertTrue(store.storedAccepted)
        assertTrue(vm.uiState.value.disclosureAccepted)

        val restored = PracticeLensViewModel(TestClock, RecordingInterpreter(), disclosureStore = store)
        assertTrue(restored.uiState.value.disclosureAccepted)

        restored.resetDisclosureForTests()
        assertFalse(store.storedAccepted)
        assertFalse(restored.uiState.value.disclosureAccepted)
        assertFalse(restored.uiState.value.scanning)
    }

    @Test fun `disclosure route precedes permission settings and camera access`() {
        assertEquals(
            PracticeLensRootRoute.DISCLOSURE,
            PracticeLensRootRouter.route(
                PracticeLensUiState(
                    disclosureAccepted = false,
                    cameraPermissionGranted = false,
                    cameraPermissionPermanentlyDenied = true,
                    scanning = true,
                ),
            ),
        )
        assertEquals(
            PracticeLensRootRoute.CAMERA_PERMISSION,
            PracticeLensRootRouter.route(
                PracticeLensUiState(
                    disclosureAccepted = true,
                    cameraPermissionGranted = false,
                    cameraPermissionPermanentlyDenied = true,
                ),
            ),
        )
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

    @Test fun `start waits for readiness focus settle and stable frame before capture`() = viewModelRunTest {
        val vm = readyVm()

        vm.startAutomaticPractice()
        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        vm.automaticCameraReady()
        vm.automaticStableFrameAccepted()

        assertEquals(AutomaticPracticeState.WAITING_FOR_FOCUS, vm.uiState.value.automaticState)
        assertEquals(0, vm.uiState.value.automaticCaptureRequestId)

        advanceTimeBy(700)
        runCurrent()

        assertEquals(AutomaticPracticeState.WAITING_FOR_FOCUS, vm.uiState.value.automaticState)
        assertEquals("Hold steady", vm.uiState.value.automaticStatus)
        assertEquals(0, vm.uiState.value.automaticCaptureRequestId)

        vm.automaticStableFrameAccepted()

        assertEquals(AutomaticPracticeState.CAPTURING, vm.uiState.value.automaticState)
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `single stability event produces one capture request`() = viewModelRunTest {
        val vm = readyVm()

        vm.startAutomaticPractice()
        repeat(8) { vm.automaticCameraReady() }
        advanceTimeBy(700)
        runCurrent()
        repeat(8) { vm.automaticStableFrameAccepted() }
        repeat(8) { vm.automaticCameraReady() }
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(AutomaticPracticeState.CAPTURING, vm.uiState.value.automaticState)
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `bounded fallback captures after four seconds with warning`() = viewModelRunTest {
        val vm = readyVm()

        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        advanceTimeBy(3_999)
        runCurrent()

        assertEquals(0, vm.uiState.value.automaticCaptureRequestId)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(AutomaticPracticeState.CAPTURING, vm.uiState.value.automaticState)
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)
        assertTrue(vm.uiState.value.automaticCaptureQualityWarning!!.contains("fallback"))
    }

    @Test fun `valid answer shows result then waits for scene change`() = viewModelRunTest {
        val interpreter = RecordingInterpreter()
        val vm = readyVm(interpreter)
        vm.setResultDisplaySeconds(3)
        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        advanceTimeBy(700)
        runCurrent()
        vm.automaticStableFrameAccepted()

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
        answerFirstQuestion(vm, "one", "0000000000000000")

        vm.automaticSceneObserved("0000000000000001")
        vm.automaticSceneObserved("0000000000000001")
        runCurrent()

        assertEquals(1, interpreter.answerCalls)
        assertEquals(AutomaticPracticeState.WAITING_FOR_SCENE_CHANGE, vm.uiState.value.automaticState)
    }

    @Test fun `first question result changed second question second result`() = viewModelRunTest {
        val interpreter = RecordingInterpreter()
        val vm = readyVm(interpreter)

        answerFirstQuestion(vm, "one", "0000000000000000")
        confirmChangedSceneAndCapture(vm, "two", "ffffffffffffffff")

        assertEquals(2, interpreter.answerCalls)
        assertEquals(AutomaticPracticeState.SHOWING_RESULT, vm.uiState.value.automaticState)
    }

    @Test fun `three complete consecutive questions use same rearm mechanism`() = viewModelRunTest {
        val interpreter = RecordingInterpreter()
        val vm = readyVm(interpreter)

        answerFirstQuestion(vm, "one", "0000000000000000")
        confirmChangedSceneAndCapture(vm, "two", "ffffffffffffffff")
        advanceTimeBy(3_000)
        runCurrent()
        confirmChangedSceneAndCapture(vm, "three", "f0f0f0f0f0f0f0f0")

        assertEquals(3, vm.uiState.value.automaticCaptureRequestId)
        assertEquals(3, interpreter.answerCalls)
    }

    @Test fun `request ceiling stops session`() = viewModelRunTest {
        val interpreter = RecordingInterpreter()
        val vm = readyVm(interpreter)
        vm.setAutomaticRequestCeiling(1)
        answerFirstQuestion(vm, "one", "0000000000000000")
        rearmAfterChangedScene(vm, "ffffffffffffffff")
        advanceTimeBy(700)
        runCurrent()
        vm.automaticStableFrameAccepted()
        vm.onAutomaticImageCaptured(media("two", "ffffffffffffffff"), requestId = vm.uiState.value.automaticCaptureRequestId)
        runCurrent()

        assertEquals(AutomaticPracticeState.CONFIGURING, vm.uiState.value.automaticState)
        assertTrue(vm.uiState.value.automaticStatus.contains("limit"))
        assertEquals(1, interpreter.answerCalls)
    }

    @Test fun `unchanged scene does not recapture`() = viewModelRunTest {
        val vm = readyVm()

        answerFirstQuestion(vm, "one", "0000000000000000")
        repeat(4) { vm.automaticSceneObserved("0000000000000001") }
        runCurrent()

        assertEquals(AutomaticPracticeState.WAITING_FOR_SCENE_CHANGE, vm.uiState.value.automaticState)
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `scene comparison uses accepted analysis fingerprint when available`() = viewModelRunTest {
        val vm = readyVm()

        vm.setResultDisplaySeconds(3)
        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        advanceTimeBy(700)
        runCurrent()
        vm.automaticSceneObserved("aaaaaaaaaaaaaaaa")
        vm.automaticStableFrameAccepted()
        vm.onAutomaticImageCaptured(media("one", "0000000000000000"), requestId = vm.uiState.value.automaticCaptureRequestId)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        vm.automaticSceneObserved("aaaaaaaaaaaaaaab")
        vm.automaticSceneObserved("aaaaaaaaaaaaaaab")

        assertEquals(AutomaticPracticeState.WAITING_FOR_SCENE_CHANGE, vm.uiState.value.automaticState)
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `one noisy changed frame does not recapture`() = viewModelRunTest {
        val vm = readyVm()

        answerFirstQuestion(vm, "one", "0000000000000000")
        vm.automaticSceneObserved("ffffffffffffffff")
        runCurrent()

        assertEquals(AutomaticPracticeState.WAITING_FOR_SCENE_CHANGE, vm.uiState.value.automaticState)
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `two changed observations rearm exactly once`() = viewModelRunTest {
        val vm = readyVm()

        answerFirstQuestion(vm, "one", "0000000000000000")
        val resetBefore = vm.uiState.value.automaticAnalysisResetId
        rearmAfterChangedScene(vm, "ffffffffffffffff")
        vm.automaticSceneObserved("ffffffffffffffff")
        vm.automaticSceneObserved("ffffffffffffffff")

        assertEquals(AutomaticPracticeState.WAITING_FOR_FOCUS, vm.uiState.value.automaticState)
        assertEquals(resetBefore + 1, vm.uiState.value.automaticAnalysisResetId)
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `fallback timer is recreated for every cycle`() = viewModelRunTest {
        val vm = readyVm()

        answerFirstQuestion(vm, "one", "0000000000000000")
        rearmAfterChangedScene(vm, "ffffffffffffffff")
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(AutomaticPracticeState.CAPTURING, vm.uiState.value.automaticState)
        assertEquals(2, vm.uiState.value.automaticCaptureRequestId)
        assertTrue(vm.uiState.value.automaticCaptureQualityWarning!!.contains("fallback"))
    }

    @Test fun `capture error resets guard and permits recovery capture`() = viewModelRunTest {
        val vm = readyVm()

        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        advanceTimeBy(700)
        runCurrent()
        vm.automaticStableFrameAccepted()
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)

        vm.automaticCaptureFailed("Capture failed")
        runCurrent()
        advanceTimeBy(700)
        runCurrent()
        vm.automaticStableFrameAccepted()

        assertEquals(AutomaticPracticeState.CAPTURING, vm.uiState.value.automaticState)
        assertEquals(2, vm.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `second cycle capture error can recover`() = viewModelRunTest {
        val vm = readyVm()

        answerFirstQuestion(vm, "one", "0000000000000000")
        rearmAfterChangedScene(vm, "ffffffffffffffff")
        advanceTimeBy(700)
        runCurrent()
        vm.automaticStableFrameAccepted()
        assertEquals(2, vm.uiState.value.automaticCaptureRequestId)

        vm.automaticCaptureFailed("Capture failed")
        advanceTimeBy(700)
        runCurrent()
        vm.automaticStableFrameAccepted()

        assertEquals(3, vm.uiState.value.automaticCaptureRequestId)
        assertEquals(AutomaticPracticeState.CAPTURING, vm.uiState.value.automaticState)
    }

    @Test fun `rotation changes during session do not duplicate or stop automatic loop`() = viewModelRunTest {
        val vm = readyVm()

        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        vm.setCaptureOrientation(CaptureOrientation.PORTRAIT)
        vm.setCaptureOrientation(CaptureOrientation.LANDSCAPE)
        vm.setCaptureOrientation(CaptureOrientation.AUTO)
        advanceTimeBy(700)
        runCurrent()
        vm.automaticStableFrameAccepted()
        vm.setCaptureOrientation(CaptureOrientation.PORTRAIT)
        vm.automaticStableFrameAccepted()

        assertEquals(CaptureOrientation.PORTRAIT, vm.uiState.value.captureOrientation)
        assertEquals(AutomaticPracticeState.CAPTURING, vm.uiState.value.automaticState)
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)
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

    @Test fun `stop and background cancel pending fallback and reset capture guard`() = viewModelRunTest {
        val vm = readyVm()

        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        vm.stopAutomaticPractice()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(AutomaticPracticeState.CONFIGURING, vm.uiState.value.automaticState)
        assertEquals(0, vm.uiState.value.automaticCaptureRequestId)

        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        vm.onBackgrounded()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(AutomaticPracticeState.CONFIGURING, vm.uiState.value.automaticState)
        assertEquals(0, vm.uiState.value.automaticCaptureRequestId)
        assertNull(vm.uiState.value.automaticCaptureQualityWarning)
    }

    @Test fun `background and stop cancel scene polling`() = viewModelRunTest {
        val vm = readyVm()

        answerFirstQuestion(vm, "one", "0000000000000000")
        vm.onBackgrounded()
        vm.automaticSceneObserved("ffffffffffffffff")
        runCurrent()

        assertEquals(AutomaticPracticeState.CONFIGURING, vm.uiState.value.automaticState)
        assertEquals(1, vm.uiState.value.automaticCaptureRequestId)

        val second = readyVm()
        answerFirstQuestion(second, "one", "0000000000000000")
        second.stopAutomaticPractice()
        second.automaticSceneObserved("ffffffffffffffff")
        runCurrent()

        assertEquals(AutomaticPracticeState.CONFIGURING, second.uiState.value.automaticState)
        assertEquals(1, second.uiState.value.automaticCaptureRequestId)
    }

    @Test fun `stop returns to configuring and cancels pending focus capture`() = viewModelRunTest {
        val vm = readyVm()

        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        assertEquals(AutomaticPracticeState.WAITING_FOR_FOCUS, vm.uiState.value.automaticState)

        vm.stopAutomaticPractice()
        advanceTimeBy(4_000)
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

    @Test fun `no duplicate model request from duplicate captured callback`() = viewModelRunTest {
        val interpreter = RecordingInterpreter()
        val vm = readyVm(interpreter)

        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        advanceTimeBy(700)
        runCurrent()
        vm.automaticStableFrameAccepted()
        vm.onAutomaticImageCaptured(media("one", "0000000000000000"))
        vm.onAutomaticImageCaptured(media("two", "ffffffffffffffff"))
        runCurrent()

        assertEquals(1, interpreter.answerCalls)
    }

    @Test fun `stale first and second cycle callbacks cannot update current cycle`() = viewModelRunTest {
        val interpreter = RecordingInterpreter()
        val vm = readyVm(interpreter)

        answerFirstQuestion(vm, "one", "0000000000000000")
        rearmAfterChangedScene(vm, "ffffffffffffffff")
        advanceTimeBy(700)
        runCurrent()
        vm.automaticStableFrameAccepted()
        val secondRequest = vm.uiState.value.automaticCaptureRequestId

        vm.onAutomaticImageCaptured(media("stale-one", "1111111111111111"), requestId = 1)
        vm.onAutomaticImageCaptured(media("two", "ffffffffffffffff"), requestId = secondRequest)
        advanceTimeBy(8_000)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        rearmAfterChangedScene(vm, "f0f0f0f0f0f0f0f0")
        advanceTimeBy(700)
        runCurrent()
        vm.automaticStableFrameAccepted()
        val thirdRequest = vm.uiState.value.automaticCaptureRequestId
        vm.onAutomaticImageCaptured(media("stale-two", "ffffffffffffffff"), requestId = secondRequest)
        vm.onAutomaticImageCaptured(media("three", "f0f0f0f0f0f0f0f0"), requestId = thirdRequest)
        advanceTimeBy(8_000)
        runCurrent()

        assertEquals(3, interpreter.answerCalls)
    }

    private fun readyVm(interpreter: RecordingInterpreter = RecordingInterpreter()) =
        PracticeLensViewModel(TestClock, interpreter).also {
            it.acceptDisclosure()
            it.setCameraPermission(true)
        }

    private suspend fun TestScope.answerFirstQuestion(vm: PracticeLensViewModel, sha: String, dhash: String) {
        vm.setResultDisplaySeconds(3)
        vm.startAutomaticPractice()
        vm.automaticCameraReady()
        advanceTimeBy(700)
        runCurrent()
        vm.automaticStableFrameAccepted()
        vm.onAutomaticImageCaptured(media(sha, dhash), requestId = vm.uiState.value.automaticCaptureRequestId)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(AutomaticPracticeState.WAITING_FOR_SCENE_CHANGE, vm.uiState.value.automaticState)
    }

    private suspend fun TestScope.rearmAfterChangedScene(vm: PracticeLensViewModel, dhash: String) {
        vm.automaticSceneObserved(dhash)
        vm.automaticSceneObserved(dhash)
        runCurrent()
        assertEquals(AutomaticPracticeState.WAITING_FOR_FOCUS, vm.uiState.value.automaticState)
    }

    private suspend fun TestScope.confirmChangedSceneAndCapture(vm: PracticeLensViewModel, sha: String, dhash: String) {
        rearmAfterChangedScene(vm, dhash)
        advanceTimeBy(700)
        runCurrent()
        vm.automaticStableFrameAccepted()
        vm.onAutomaticImageCaptured(media(sha, dhash), requestId = vm.uiState.value.automaticCaptureRequestId)
        advanceTimeBy(8_000)
        runCurrent()
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

    private class RecordingDisclosureStore(initial: Boolean) : DisclosureAcceptanceStore {
        var storedAccepted = initial
        override fun isAccepted(): Boolean = storedAccepted
        override fun setAccepted(accepted: Boolean) {
            storedAccepted = accepted
        }
        override fun reset() {
            storedAccepted = false
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
