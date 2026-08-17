package app.practicelens.android.camera

import android.content.Context
import app.practicelens.android.core.CapturedQuestionMedia
import app.practicelens.android.ocr.AndroidCropOcrReader
import app.practicelens.android.ocr.OcrObservation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidCropOcrProcessor(context: Context) : CropOcrProcessor {
    private val mediaProcessor = AndroidQuestionMediaProcessor(context)
    private val ocrReader = AndroidCropOcrReader()

    override suspend fun process(
        source: CapturedQuestionMedia,
        crop: NormalizedCropRect,
        rotationDegrees: Int,
    ): CropOcrResult {
        val cropped = mediaProcessor.crop(source, crop, rotationDegrees)
        return try {
            val observation = recognize(cropped)
            CropOcrResult(cropped, observation)
        } catch (e: kotlinx.coroutines.CancellationException) {
            mediaProcessor.delete(cropped)
            throw e
        } catch (t: Throwable) {
            CropOcrResult(cropped, null)
        }
    }

    override fun delete(media: CapturedQuestionMedia) {
        mediaProcessor.delete(media)
    }

    fun close() {
        ocrReader.close()
    }

    private suspend fun recognize(media: CapturedQuestionMedia): OcrObservation? =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { mediaProcessor.delete(media) }
            ocrReader.recognize(media) { result ->
                if (!continuation.isActive) {
                    mediaProcessor.delete(media)
                } else {
                    continuation.resume(result.getOrThrow())
                }
            }
        }
}
