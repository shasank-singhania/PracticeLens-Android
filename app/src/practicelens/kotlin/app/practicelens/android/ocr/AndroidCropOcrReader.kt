package app.practicelens.android.ocr

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import androidx.core.net.toFile
import app.practicelens.android.core.CapturedQuestionMedia
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class AndroidCropOcrReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun recognize(media: CapturedQuestionMedia, onComplete: (Result<OcrObservation>) -> Unit) {
        val bitmap = BitmapFactory.decodeFile(Uri.parse(media.uri).toFile().absolutePath)
        if (bitmap == null) {
            onComplete(Result.failure(IllegalStateException("Captured image is unavailable.")))
            return
        }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnCompleteListener { task ->
                bitmap.recycle()
                if (!task.isSuccessful) {
                    onComplete(Result.failure(task.exception ?: IllegalStateException("OCR was canceled.")))
                    return@addOnCompleteListener
                }
                val text = task.result
                val blocks = text.textBlocks.map { block ->
                    val blockBox = block.boundingBox ?: Rect()
                    val lines = block.lines.map { line ->
                        val box = line.boundingBox ?: Rect()
                        OcrTextLine(
                            text = line.text,
                            left = box.left,
                            top = box.top,
                            right = box.right,
                            bottom = box.bottom,
                            elements = line.elements.map { element ->
                                val elementBox = element.boundingBox ?: Rect()
                                OcrTextElement(
                                    text = element.text,
                                    left = elementBox.left,
                                    top = elementBox.top,
                                    right = elementBox.right,
                                    bottom = elementBox.bottom,
                                    confidence = element.confidence,
                                )
                            },
                            confidence = line.confidence,
                            recognizedLanguage = line.recognizedLanguage,
                        )
                    }
                    OcrTextBlock(
                        text = block.text,
                        left = blockBox.left,
                        top = blockBox.top,
                        right = blockBox.right,
                        bottom = blockBox.bottom,
                        lines = lines,
                        recognizedLanguage = block.recognizedLanguage,
                    )
                }
                onComplete(Result.success(OcrObservation(text.text, blocks.flatMap { it.lines }, blocks, 0, media.width, media.height)))
            }
    }

    fun close() {
        recognizer.close()
    }
}
