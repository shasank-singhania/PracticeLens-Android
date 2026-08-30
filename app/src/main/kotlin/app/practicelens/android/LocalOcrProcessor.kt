package app.practicelens.android

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

interface LocalOcrProcessor {
    suspend fun recognize(imageFile: File): OcrResult
}

class MlKitLocalOcrProcessor(
    private val context: Context,
) : LocalOcrProcessor {
    override suspend fun recognize(imageFile: File): OcrResult {
        val image = InputImage.fromFilePath(context, Uri.fromFile(imageFile))
        val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image).await()
        return text.toOcrResult()
    }
}

class NoOpLocalOcrProcessor : LocalOcrProcessor {
    override suspend fun recognize(imageFile: File): OcrResult = OcrResult()
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

private fun Text.toOcrResult(): OcrResult {
    var order = 0
    val blocks = textBlocks.map { block ->
        OcrBlock(
            text = block.text,
            bounds = block.boundingBox.toTextBounds(),
            cornerPoints = block.cornerPoints.toTextPoints(),
            lines = block.lines.map { line ->
                val lineOrder = order++
                OcrLine(
                    text = line.text,
                    bounds = line.boundingBox.toTextBounds(),
                    cornerPoints = line.cornerPoints.toTextPoints(),
                    confidence = line.optionalFloat("getConfidence"),
                    readingOrder = lineOrder,
                    elements = line.elements.map { element ->
                        OcrElement(
                            text = element.text,
                            bounds = element.boundingBox.toTextBounds(),
                            cornerPoints = element.cornerPoints.toTextPoints(),
                            confidence = element.optionalFloat("getConfidence"),
                            symbols = element.optionalList("getSymbols").map { symbol ->
                                OcrSymbol(
                                    text = symbol.optionalString("getText"),
                                    bounds = symbol.optionalRect("getBoundingBox").toTextBounds(),
                                    cornerPoints = symbol.optionalPoints("getCornerPoints").toTextPoints(),
                                    confidence = symbol.optionalFloat("getConfidence"),
                                )
                            },
                        )
                    },
                )
            },
        )
    }
    val candidates = blocks.flatMap { block ->
        block.lines.map { line ->
            OcrCandidate(
                text = line.text,
                marker = line.elements.firstOrNull()?.text ?: firstMarkerToken(line.text),
                position = null,
                confidence = line.confidence,
                bounds = line.bounds,
                readingOrder = line.readingOrder,
            )
        }
    }
    return OcrResult(blocks = blocks, candidates = candidates)
}

private fun firstMarkerToken(text: String): String? =
    text.trim().split(Regex("""\s+""")).firstOrNull()?.takeIf { it.isNotBlank() }

private fun Rect?.toTextBounds(): TextBounds? = this?.let { TextBounds(it.left, it.top, it.right, it.bottom) }
private fun Array<Point>?.toTextPoints(): List<TextPoint> = this?.map { TextPoint(it.x, it.y) }.orEmpty()

private fun Any.optionalString(methodName: String): String =
    runCatching { javaClass.getMethod(methodName).invoke(this) as? String }.getOrNull().orEmpty()

private fun Any.optionalFloat(methodName: String): Float? =
    runCatching {
        when (val value = javaClass.getMethod(methodName).invoke(this)) {
            is Number -> value.toFloat()
            else -> null
        }
    }.getOrNull()

private fun Any.optionalRect(methodName: String): Rect? =
    runCatching { javaClass.getMethod(methodName).invoke(this) as? Rect }.getOrNull()

private fun Any.optionalPoints(methodName: String): Array<Point>? =
    @Suppress("UNCHECKED_CAST")
    (runCatching { javaClass.getMethod(methodName).invoke(this) as? Array<Point> }.getOrNull())

private fun Any.optionalList(methodName: String): List<Any> =
    @Suppress("UNCHECKED_CAST")
    (runCatching { javaClass.getMethod(methodName).invoke(this) as? List<Any> }.getOrNull()).orEmpty()
