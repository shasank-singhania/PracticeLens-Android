package app.practicelens.android

import app.practicelens.android.camera.CropReviewGeometry
import app.practicelens.android.camera.ImageDecodePolicy
import app.practicelens.android.camera.NormalizedCropRect
import app.practicelens.android.camera.QuestionMediaCachePolicy
import app.practicelens.android.core.CapturedQuestionMedia
import app.practicelens.android.core.QuestionMediaJanitor
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropAndMediaPolicyTest {
    @Test fun `all rotations produce deterministic dimensions and round trips`() {
        assertEquals(1200 to 900, CropReviewGeometry.rotatedDimensions(1200, 900, 0))
        assertEquals(900 to 1200, CropReviewGeometry.rotatedDimensions(1200, 900, 90))
        assertEquals(1200 to 900, CropReviewGeometry.rotatedDimensions(1200, 900, 180))
        assertEquals(900 to 1200, CropReviewGeometry.rotatedDimensions(1200, 900, 270))
        assertEquals(1200 to 900, CropReviewGeometry.rotatedDimensions(1200, 900, 360))
    }

    @Test fun `edge corner minimum and full frame crops stay in bounds`() {
        val imageWidth = 1000
        val imageHeight = 800
        val crops = listOf(
            NormalizedCropRect(0f, 0f, 0.12f, 0.12f),
            NormalizedCropRect(0.88f, 0f, 1f, 0.12f),
            NormalizedCropRect(0f, 0.88f, 0.12f, 1f),
            NormalizedCropRect(0.88f, 0.88f, 1f, 1f),
            CropReviewGeometry.fullImage(),
            CropReviewGeometry.move(NormalizedCropRect(0.2f, 0.2f, 0.4f, 0.4f), -1f, -1f),
            CropReviewGeometry.move(NormalizedCropRect(0.6f, 0.6f, 0.8f, 0.8f), 1f, 1f),
            CropReviewGeometry.resize(NormalizedCropRect(0.45f, 0.45f, 0.55f, 0.55f), 1f, 1f, -1f, -1f),
        )

        crops.forEach { crop ->
            val pixels = CropReviewGeometry.toPixels(crop, imageWidth, imageHeight)
            assertTrue(pixels.left >= 0)
            assertTrue(pixels.top >= 0)
            assertTrue(pixels.left + pixels.width <= imageWidth)
            assertTrue(pixels.top + pixels.height <= imageHeight)
            assertTrue(pixels.width >= 1)
            assertTrue(pixels.height >= 1)
        }
        assertEquals(1000, CropReviewGeometry.toPixels(CropReviewGeometry.fullImage(), imageWidth, imageHeight).width)
        assertEquals(800, CropReviewGeometry.toPixels(CropReviewGeometry.fullImage(), imageWidth, imageHeight).height)
    }

    @Test fun `large image decode policy bounds memory without aggressive sampling`() {
        assertEquals(1, ImageDecodePolicy.sampleSize(1600, 1200))
        assertEquals(2, ImageDecodePolicy.sampleSize(4800, 3600))
        assertEquals(4, ImageDecodePolicy.sampleSize(9000, 7000))
    }

    @Test fun `cache cleanup only deletes old app owned media files`() {
        val cache = Files.createTempDirectory("pl-cache").toFile()
        try {
            val captures = cache.resolve("question-captures").apply { mkdirs() }
            val crops = cache.resolve("question-crops").apply { mkdirs() }
            val other = cache.resolve("other").apply { mkdirs() }
            val oldCapture = captures.resolve("capture-old.jpg").apply { writeText("old") }
            val oldCrop = crops.resolve("crop-old.jpg").apply { writeText("old") }
            val newCapture = captures.resolve("capture-new.jpg").apply { writeText("new") }
            val foreign = other.resolve("capture-old.jpg").apply { writeText("foreign") }
            oldCapture.setLastModified(1)
            oldCrop.setLastModified(1)
            newCapture.setLastModified(18_000)
            foreign.setLastModified(1)

            val deleted = QuestionMediaCachePolicy.clean(cache, nowMs = 20_000, maxAgeMs = 5_000)

            assertEquals(2, deleted)
            assertFalse(oldCapture.exists())
            assertFalse(oldCrop.exists())
            assertTrue(newCapture.exists())
            assertTrue(foreign.exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test fun `media janitor deletes active media idempotently by uri`() {
        val deleted = mutableListOf<String>()
        QuestionMediaJanitor.install { deleted += it.uri }
        try {
            val media = media("same")
            QuestionMediaJanitor.deleteAll(listOf(media, media.copy(sha256 = "other"), null))
        } finally {
            QuestionMediaJanitor.clear()
        }

        assertEquals(listOf("file:///tmp/same.jpg"), deleted)
    }

    private fun media(name: String) = CapturedQuestionMedia(
        uri = "file:///tmp/$name.jpg",
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        appliedRotationDegrees = 0,
        sha256 = name,
        capturedAtMs = 1,
    )
}
