package app.practicelens.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerationConfig
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.UsageMetadata
import java.io.File
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class FirebaseGeminiGateway(
    private val modelId: String = BuildConfig.GEMINI_MODEL_ID,
) : GeminiGateway {
    override suspend fun answerImage(imageFile: File): GeminiResponse {
        val bitmap = decodeBoundedBitmap(imageFile)
            ?: throw GeminiRequestException(GeminiFailureCategory.CONFIGURATION, "Could not decode captured image.")
        return try {
            val model = FirebaseAI.getInstance(FirebaseApp.getInstance(), GenerativeBackend.googleAI())
                .generativeModel(modelId, generationConfig())
            val response = withTimeout(60_000) {
                model.generateContent(Content("user", listOf(TextPart(PROMPT), ImagePart(bitmap))))
            }
            GeminiResponse(
                rawText = response.text.orEmpty(),
                finishReason = response.candidates.firstOrNull()?.finishReason?.name,
                tokenMetadata = response.usageMetadata.toTokenMetadata(),
            )
        } catch (t: Throwable) {
            throw mapGeminiFailure(t)
        } finally {
            bitmap.recycle()
        }
    }

    private fun generationConfig(): GenerationConfig =
        GenerationConfig.builder()
            .setTemperature(0.1f)
            .setCandidateCount(1)
            .setMaxOutputTokens(65_536)
            .build()

    private fun UsageMetadata?.toTokenMetadata(): GeminiTokenMetadata =
        GeminiTokenMetadata(
            promptTokens = this?.promptTokenCount,
            outputTokens = this?.candidatesTokenCount,
            thinkingTokens = this?.thoughtsTokenCount,
            totalTokens = this?.totalTokenCount,
        )

    private fun decodeBoundedBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val maxDimension = 1600
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) },
        )
    }

    private fun mapGeminiFailure(t: Throwable): GeminiRequestException {
        if (t is GeminiRequestException) return t
        val text = listOfNotNull(t.message, t.cause?.message).joinToString(" ").lowercase()
        val category = when {
            t is TimeoutCancellationException || "timeout" in text || "deadline" in text -> GeminiFailureCategory.TIMEOUT
            t is FirebaseNetworkException || "network" in text -> GeminiFailureCategory.NETWORK
            "app attestation" in text || "app check" in text || "appcheck" in text -> GeminiFailureCategory.APP_CHECK_REQUIRED
            "quota" in text || "resource_exhausted" in text -> GeminiFailureCategory.QUOTA
            "api key" in text || "firebaseapp" in text || "google-services" in text || "not configured" in text -> GeminiFailureCategory.CONFIGURATION
            else -> GeminiFailureCategory.UNKNOWN
        }
        return GeminiRequestException(category, t.message ?: category.name, t)
    }

    companion object {
        const val PROMPT: String =
            "Inspect the entire image and answer every visible multiple-choice question in natural reading order. " +
                "Return one short line per question using: Q<number>: position <1-based option position> | marker <verbatim visible marker or NONE> | <exact selected option text>. " +
                "The option marker may be a number, letter, word, bullet, symbol, Unicode character, checkbox, radio-button label, or another visible identifier. " +
                "If no marker can be read, use NONE and still provide the option position and exact option text. " +
                "Do not repeat the full question. " +
                "Do not provide explanations, reasoning, JSON, Markdown tables, headings, or commentary."
    }
}
