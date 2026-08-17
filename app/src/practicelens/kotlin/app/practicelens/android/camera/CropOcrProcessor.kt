package app.practicelens.android.camera

import app.practicelens.android.core.CapturedQuestionMedia
import app.practicelens.android.ocr.OcrObservation

data class CropOcrResult(
    val croppedMedia: CapturedQuestionMedia,
    val observation: OcrObservation?,
)

interface CropOcrProcessor {
    suspend fun process(
        source: CapturedQuestionMedia,
        crop: NormalizedCropRect,
        rotationDegrees: Int,
    ): CropOcrResult

    fun delete(media: CapturedQuestionMedia)
}
