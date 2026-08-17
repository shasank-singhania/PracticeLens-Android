package app.practicelens.android.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import app.practicelens.android.core.CapturedQuestionMedia
import app.practicelens.android.core.sha256
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

class AndroidQuestionImagePreparer(
    private val context: Context,
    private val maxLongEdge: Int = 1_800,
) {
    fun prepare(rawFile: File, warning: String? = null): CapturedQuestionMedia {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(rawFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Captured image is unreadable.")
        val exifRotation = QuestionImagePreparationCore.exifToDegrees(
            ExifInterface(rawFile.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            ),
        )
        val sample = ImageDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge * 2)
        val decoded = BitmapFactory.decodeFile(
            rawFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: error("Captured image is unavailable.")
        val rotated = decoded.rotate(exifRotation)
        if (rotated !== decoded) decoded.recycle()
        val resized = rotated.scaleLongEdge(maxLongEdge)
        if (resized !== rotated) rotated.recycle()
        val qualityWarnings = QuestionImagePreparationCore.qualityWarnings(resized.width, resized.height, averageLuma(resized))
        val dhash = QuestionImagePreparationCore.dHash(resized)
        val output = createPreparedFile()
        output.outputStream().use { stream -> resized.compress(Bitmap.CompressFormat.JPEG, 92, stream) }
        val bytes = output.readBytes()
        val media = CapturedQuestionMedia(
            uri = Uri.fromFile(output).toString(),
            mimeType = "image/jpeg",
            width = resized.width,
            height = resized.height,
            appliedRotationDegrees = exifRotation,
            sha256 = sha256(bytes),
            capturedAtMs = System.currentTimeMillis(),
            qualityWarnings = listOfNotNull(warning) + qualityWarnings + "dhash:$dhash",
        )
        resized.recycle()
        rawFile.delete()
        return media
    }

    private fun createPreparedFile(): File {
        val dir = File(context.cacheDir, "question-captures").apply { mkdirs() }
        return File(dir, "capture-normalized-${System.currentTimeMillis()}-${System.nanoTime()}.jpg")
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return this
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun Bitmap.scaleLongEdge(maxLongEdge: Int): Bitmap {
        val currentLongEdge = max(width, height)
        if (currentLongEdge <= maxLongEdge) return this
        val scale = maxLongEdge.toFloat() / currentLongEdge.toFloat()
        return Bitmap.createScaledBitmap(this, (width * scale).roundToInt(), (height * scale).roundToInt(), true)
    }

    private fun averageLuma(bitmap: Bitmap): Double {
        val stepX = max(1, bitmap.width / 32)
        val stepY = max(1, bitmap.height / 32)
        var total = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                total += QuestionImagePreparationCore.luma(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 0.0 else total / count
    }
}

object QuestionImagePreparationCore {
    fun exifToDegrees(orientation: Int): Int =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    fun rotatedDimensions(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (((rotationDegrees % 180) + 180) % 180 == 90) height to width else width to height

    fun qualityWarnings(width: Int, height: Int, averageLuma: Double): List<String> =
        buildList {
            if (width < 800 || height < 600) add("Image is low resolution; move closer if text is unreadable.")
            if (averageLuma < 38.0) add("Image may be underexposed.")
            if (averageLuma > 232.0) add("Image may be overexposed or affected by glare.")
        }

    fun luma(red: Int, green: Int, blue: Int): Double = 0.299 * red + 0.587 * green + 0.114 * blue

    fun hammingDistance(left: String?, right: String?): Int {
        if (left.isNullOrBlank() || right.isNullOrBlank() || left.length != right.length) return Int.MAX_VALUE
        return left.zip(right).sumOf { (a, b) -> (a.digitToInt(16) xor b.digitToInt(16)).countOneBits() }
    }

    fun dHash(bitmap: Bitmap): String {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var bits = 0UL
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = small.getPixel(x, y).gray()
                val right = small.getPixel(x + 1, y).gray()
                bits = (bits shl 1) or if (left > right) 1UL else 0UL
            }
        }
        if (small !== bitmap) small.recycle()
        return bits.toString(16).padStart(16, '0')
    }

    private fun Int.gray(): Int = luma(Color.red(this), Color.green(this), Color.blue(this)).roundToInt()
}
