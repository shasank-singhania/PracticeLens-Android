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
import kotlin.math.max

class AndroidQuestionMediaProcessor(private val context: Context) {
    fun crop(source: CapturedQuestionMedia, crop: NormalizedCropRect, rotationDegrees: Int): CapturedQuestionMedia {
        val sourceFile = Uri.parse(source.uri).toFile()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
        val bitmap = BitmapFactory.decodeFile(
            sourceFile.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = ImageDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight)
            },
        )
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
        runCatching {
            val file = Uri.parse(uri).toFile()
            if (isPracticeLensMediaFile(file)) file.delete()
        }
    }

    fun cleanOrphanedCacheFiles(maxAgeMs: Long = QuestionMediaCachePolicy.OrphanMaxAgeMs): Int =
        QuestionMediaCachePolicy.clean(context.cacheDir, System.currentTimeMillis(), maxAgeMs)

    private fun isPracticeLensMediaFile(file: File): Boolean =
        QuestionMediaCachePolicy.isPracticeLensMediaFile(context.cacheDir, file)

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

object ImageDecodePolicy {
    private const val MaxDecodedDimension = 2_400

    fun sampleSize(width: Int, height: Int, maxDecodedDimension: Int = MaxDecodedDimension): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (max(width / sample, height / sample) > maxDecodedDimension) {
            sample *= 2
        }
        return sample
    }
}

object QuestionMediaCachePolicy {
    const val OrphanMaxAgeMs: Long = 24L * 60L * 60L * 1_000L
    private val OwnedDirs = setOf("question-captures", "question-crops")

    fun isPracticeLensMediaFile(cacheDir: File, file: File): Boolean {
        val parent = file.parentFile ?: return false
        return parent.parentFile?.canonicalPath == cacheDir.canonicalPath &&
            parent.name in OwnedDirs &&
            file.extension.equals("jpg", ignoreCase = true)
    }

    fun clean(cacheDir: File, nowMs: Long, maxAgeMs: Long = OrphanMaxAgeMs): Int {
        var deleted = 0
        OwnedDirs.forEach { name ->
            val dir = File(cacheDir, name)
            dir.listFiles()?.forEach { file ->
                if (file.isFile && isPracticeLensMediaFile(cacheDir, file) && nowMs - file.lastModified() >= maxAgeMs) {
                    if (file.delete()) deleted++
                }
            }
        }
        return deleted
    }
}
