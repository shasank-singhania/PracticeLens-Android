package app.practicelens.android

import app.practicelens.android.camera.CropReviewGeometry
import app.practicelens.android.camera.CropOcrProcessor
import app.practicelens.android.camera.CropOcrResult
import app.practicelens.android.camera.NormalizedCropRect
import app.practicelens.android.core.CapturedQuestionMedia
import app.practicelens.android.core.MonotonicClock
import app.practicelens.android.interpretation.InterpretationStatus
import app.practicelens.android.interpretation.QuestionImageInterpreter
import app.practicelens.android.interpretation.QuestionInterpretation
import app.practicelens.android.interpretation.InterpretedOption
import app.practicelens.android.ocr.OcrObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageFirstWorkflowTest {
    @Test fun `manual capture opens crop review without OCR validity`() {
        val vm = PracticeLensViewModel(FakeClock, NeverInterpreter)
        val media = media("image-1")

        vm.openCropReview(media)

        assertFalse(vm.uiState.value.scanning)
        assertEquals(media, vm.uiState.value.capturedImage)
        assertNull(vm.uiState.value.ocrReview)
    }

    @Test fun `invalid OCR after confirmed crop stays in question review`() = viewModelRunTest {
        val vm = PracticeLensViewModel(FakeClock, NeverInterpreter)
        val processor = ControlledCropOcrProcessor()
        vm.openCropReview(media("image-1"))

        vm.startCropOcr(media("image-1"), CropReviewGeometry.initialGuide(), 0, processor)
        runCurrent()
        processor.requests.single().response.complete(CropOcrResult(media("crop-1"), OcrObservation("Only a formula α + β = γ")))
        runCurrent()

        assertFalse(vm.uiState.value.scanning)
        assertNotNull(vm.uiState.value.croppedImage)
        assertFalse(vm.uiState.value.ocrReview?.valid ?: true)
        assertNull(vm.uiState.value.attempt)
    }

    @Test fun `confirmed question preserves crop media and uses app owned option ids`() = viewModelRunTest {
        val vm = PracticeLensViewModel(FakeClock, NeverInterpreter)
        val processor = ControlledCropOcrProcessor()
        val crop = media("crop-1")
        vm.openCropReview(media("image-1"))
        vm.startCropOcr(media("image-1"), CropReviewGeometry.initialGuide(), 0, processor)
        runCurrent()
        processor.requests.single().response.complete(CropOcrResult(crop, OcrObservation("Question\nA. One\nB. Two")))
        runCurrent()

        vm.confirmOcrDraft()

        val question = vm.uiState.value.attempt?.question
        assertEquals(crop.sha256, question?.media?.sha256)
        assertEquals(listOf("option-0", "option-1"), question?.options?.map { it.id })
        assertTrue(question?.sourceFingerprint?.isNotBlank() == true)
    }

    @Test fun `late crop OCR from retaken capture is discarded and deleted`() = viewModelRunTest {
        val processor = NonCooperativeCropOcrProcessor()
        val vm = PracticeLensViewModel(FakeClock, NeverInterpreter)
        val captureA = media("capture-a")
        val captureB = media("capture-b")
        val staleCrop = media("stale-crop")

        vm.openCropReview(captureA)
        vm.startCropOcr(captureA, CropReviewGeometry.initialGuide(), 0, processor)
        runCurrent()
        vm.retake()
        vm.openCropReview(captureB)
        processor.requests.single().response.complete(CropOcrResult(staleCrop, OcrObservation("Old?\nA. One\nB. Two")))
        runCurrent()

        assertEquals(captureB.sha256, vm.uiState.value.capturedImage?.sha256)
        assertNull(vm.uiState.value.croppedImage)
        assertNull(vm.uiState.value.ocrReview)
        assertEquals(listOf(staleCrop.sha256), processor.deleted.map { it.sha256 })
    }

    @Test fun `out of order crop OCR keeps latest capture only`() = viewModelRunTest {
        val processor = NonCooperativeCropOcrProcessor()
        val vm = PracticeLensViewModel(FakeClock, NeverInterpreter)
        val captureA = media("capture-a")
        val captureB = media("capture-b")
        val cropA = media("crop-a")
        val cropB = media("crop-b")

        vm.openCropReview(captureA)
        vm.startCropOcr(captureA, CropReviewGeometry.initialGuide(), 0, processor)
        runCurrent()
        vm.openCropReview(captureB)
        vm.startCropOcr(captureB, CropReviewGeometry.initialGuide(), 0, processor)
        runCurrent()
        processor.requests[1].response.complete(CropOcrResult(cropB, OcrObservation("New?\nA. Red\nB. Blue")))
        runCurrent()
        processor.requests[0].response.complete(CropOcrResult(cropA, OcrObservation("Old?\nA. One\nB. Two")))
        runCurrent()

        assertEquals(cropB.sha256, vm.uiState.value.croppedImage?.sha256)
        assertEquals("New?", vm.uiState.value.ocrReview?.question)
        assertEquals(listOf(cropA.sha256), processor.deleted.map { it.sha256 })
    }

    @Test fun `current crop OCR result still succeeds`() = viewModelRunTest {
        val processor = ControlledCropOcrProcessor()
        val vm = PracticeLensViewModel(FakeClock, NeverInterpreter)
        val capture = media("capture")
        val crop = media("crop")

        vm.openCropReview(capture)
        vm.startCropOcr(capture, CropReviewGeometry.initialGuide(), 90, processor)
        runCurrent()
        processor.requests.single().response.complete(CropOcrResult(crop, OcrObservation("Current?\nA. One\nB. Two")))
        runCurrent()

        assertEquals(crop.sha256, vm.uiState.value.croppedImage?.sha256)
        assertEquals("Current?", vm.uiState.value.ocrReview?.question)
        assertTrue(processor.deleted.isEmpty())
    }

    @Test fun `backgrounding cancels pending crop OCR work`() = viewModelRunTest {
        val processor = ControlledCropOcrProcessor()
        val vm = PracticeLensViewModel(FakeClock, NeverInterpreter)
        val capture = media("capture")

        vm.openCropReview(capture)
        vm.startCropOcr(capture, CropReviewGeometry.initialGuide(), 0, processor)
        runCurrent()
        vm.onBackgrounded()
        runCurrent()

        assertEquals(1, processor.cancelled)
        assertFalse(vm.uiState.value.cropOcrLoading)
        assertNull(vm.uiState.value.croppedImage)
    }

    @Test fun `disposal cancels pending crop OCR work`() = viewModelRunTest {
        val processor = ControlledCropOcrProcessor()
        val vm = PracticeLensViewModel(FakeClock, NeverInterpreter)
        val capture = media("capture")

        vm.openCropReview(capture)
        vm.startCropOcr(capture, CropReviewGeometry.initialGuide(), 0, processor)
        runCurrent()
        PracticeLensViewModel::class.java.getDeclaredMethod("onCleared").apply {
            isAccessible = true
            invoke(vm)
        }
        runCurrent()

        assertEquals(1, processor.cancelled)
    }

    @Test fun `late cancelled interpreter result cannot update stale review state`() = viewModelRunTest {
        val interpreter = NonCooperativeInterpreter()
        val processor = ControlledCropOcrProcessor()
        val vm = PracticeLensViewModel(FakeClock, interpreter)
        val captureA = media("capture-a")
        val cropA = media("crop-a")
        val captureB = media("capture-b")

        vm.openCropReview(captureA)
        vm.startCropOcr(captureA, CropReviewGeometry.initialGuide(), 0, processor)
        runCurrent()
        processor.requests.single().response.complete(CropOcrResult(cropA, OcrObservation("Old?\nA. One\nB. Two")))
        runCurrent()
        vm.analyzeImageWithAi()
        runCurrent()
        vm.retake()
        vm.openCropReview(captureB)
        interpreter.requests.single().complete(
            QuestionInterpretation(
                status = InterpretationStatus.READY,
                questionText = "Stale AI question?",
                options = listOf(InterpretedOption(0, "A", "One"), InterpretedOption(1, "B", "Two")),
                confidence = 0.9,
            ),
        )
        runCurrent()

        assertEquals(captureB.sha256, vm.uiState.value.capturedImage?.sha256)
        assertNull(vm.uiState.value.ocrReview)
        assertNull(vm.uiState.value.croppedImage)
    }

    @Test fun `crop bounds clamp and rotate dimensions are deterministic`() {
        val crop = CropReviewGeometry.resize(
            NormalizedCropRect(0.45f, 0.45f, 0.55f, 0.55f),
            left = 0.2f,
            top = 0.2f,
            right = -0.2f,
            bottom = -0.2f,
        )
        val pixels = CropReviewGeometry.toPixels(crop, 1000, 500)

        assertTrue(pixels.width >= 120)
        assertTrue(pixels.height >= 60)
        assertEquals(500 to 1000, CropReviewGeometry.rotatedDimensions(1000, 500, 90))
        assertEquals(1000 to 500, CropReviewGeometry.rotatedDimensions(1000, 500, 180))
    }

    private fun media(sha: String) = CapturedQuestionMedia(
        uri = "file:///tmp/$sha.jpg",
        mimeType = "image/jpeg",
        width = 1200,
        height = 900,
        appliedRotationDegrees = 0,
        sha256 = sha,
        capturedAtMs = 1,
    )

    private object FakeClock : MonotonicClock {
        override fun nowMs(): Long = 42
    }

    private object NeverInterpreter : QuestionImageInterpreter {
        override suspend fun interpret(image: CapturedQuestionMedia, optionalOcrText: String?): QuestionInterpretation =
            QuestionInterpretation(InterpretationStatus.RETAKE_REQUIRED, "", emptyList(), 0.0)
    }

    private class NonCooperativeInterpreter : QuestionImageInterpreter {
        val requests = mutableListOf<CompletableDeferred<QuestionInterpretation>>()

        override suspend fun interpret(image: CapturedQuestionMedia, optionalOcrText: String?): QuestionInterpretation {
            val request = CompletableDeferred<QuestionInterpretation>()
            requests += request
            return try {
                request.await()
            } catch (e: CancellationException) {
                withContext(NonCancellable) { request.await() }
            }
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

    private class ControlledCropOcrProcessor : CropOcrProcessor {
        val requests = mutableListOf<Request>()
        val deleted = mutableListOf<CapturedQuestionMedia>()
        var cancelled = 0

        override suspend fun process(
            source: CapturedQuestionMedia,
            crop: NormalizedCropRect,
            rotationDegrees: Int,
        ): CropOcrResult {
            val request = Request(source, CompletableDeferred())
            requests += request
            return try {
                request.response.await()
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
        }

        override fun delete(media: CapturedQuestionMedia) {
            deleted += media
        }
    }

    private class NonCooperativeCropOcrProcessor : CropOcrProcessor {
        val requests = mutableListOf<Request>()
        val deleted = mutableListOf<CapturedQuestionMedia>()

        override suspend fun process(
            source: CapturedQuestionMedia,
            crop: NormalizedCropRect,
            rotationDegrees: Int,
        ): CropOcrResult {
            val request = Request(source, CompletableDeferred())
            requests += request
            return try {
                request.response.await()
            } catch (e: CancellationException) {
                withContext(NonCancellable) { request.response.await() }
            }
        }

        override fun delete(media: CapturedQuestionMedia) {
            deleted += media
        }
    }

    private data class Request(
        val source: CapturedQuestionMedia,
        val response: CompletableDeferred<CropOcrResult>,
    )
}
