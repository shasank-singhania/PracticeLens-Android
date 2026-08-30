package app.practicelens.android

import java.io.File

interface GeminiGateway {
    suspend fun answerImage(imageFile: File): GeminiResponse
}

data class GeminiResponse(
    val rawText: String,
    val finishReason: String? = null,
    val tokenMetadata: GeminiTokenMetadata = GeminiTokenMetadata(),
)

data class GeminiTokenMetadata(
    val promptTokens: Int? = null,
    val outputTokens: Int? = null,
    val thinkingTokens: Int? = null,
    val totalTokens: Int? = null,
)

enum class GeminiFailureCategory {
    APP_CHECK_REQUIRED,
    NETWORK,
    TIMEOUT,
    QUOTA,
    CONFIGURATION,
    UNKNOWN,
}

class GeminiRequestException(
    val category: GeminiFailureCategory,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
