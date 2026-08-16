package app.practicelens.android

import app.practicelens.android.core.MonotonicClock
import app.practicelens.android.ocr.OcrObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeLensViewModelTest {
    @Test fun `disclosure precedes permission-driven scanning`() {
        val vm = PracticeLensViewModel(FakeClock)

        vm.setCameraPermission(true)
        assertFalse(vm.uiState.value.scanning)

        vm.acceptDisclosure()
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

    private object FakeClock : MonotonicClock {
        override fun nowMs(): Long = 42
    }
}
