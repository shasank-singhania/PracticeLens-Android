package app.practicelens.android.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.net.toFile
import app.practicelens.android.core.CapturedQuestionMedia
import app.practicelens.android.core.sha256
import java.io.File

class AndroidQuestionMediaProcessor(private val context: Context) {
    fun crop(source: CapturedQuestionMedia, crop: NormalizedCropRect, rotationDegrees: Int): CapturedQuestionMedia {
        val sourceFile = Uri.parse(source.uri).toFile()
        val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
            ?: error("Captured image is unavailable.")
        val rotated = bitmap.rotate(rotationDegrees)
        if (rotated !== bitmap) bitmap.recycle()
        val pixelCrop = CropReviewGeometry.toPixels(crop, rotated.width, rotated.height)
        val cropped = Bitmap.createBitmap(rotated, pixelCrop.left, pixelCrop.top, pixelCrop.width, pixelCrop.height)
        if (cropped !== rotated) rotated.recycle()
        val output = createCropFile()
        output.outputStream().use { stream ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }
        val bytes = output.readBytes()
        val media = CapturedQuestionMedia(
            uri = Uri.fromFile(output).toString(),
            mimeType = "image/jpeg",
            width = cropped.width,
            height = cropped.height,
            appliedRotationDegrees = rotationDegrees,
            sha256 = sha256(bytes),
            capturedAtMs = System.currentTimeMillis(),
            qualityWarnings = source.qualityWarnings,
        )
        cropped.recycle()
        return media
    }

    fun delete(media: CapturedQuestionMedia?) {
        val uri = media?.uri ?: return
        runCatching { Uri.parse(uri).toFile().delete() }
    }

    private fun createCropFile(): File {
        val dir = File(context.cacheDir, "question-crops").apply { mkdirs() }
        return File(dir, "crop-${System.currentTimeMillis()}-${System.nanoTime()}.jpg")
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return this
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
}
