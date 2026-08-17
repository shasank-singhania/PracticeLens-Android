package app.practicelens.android.interpretation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

fun rethrowIfCoroutineCancellation(t: Throwable) {
    when (t) {
        is TimeoutCancellationException -> throw t
        is CancellationException -> throw t
    }
}

fun mapModelGatewayFailure(
    t: Throwable,
    operation: String,
    isNetworkFailure: Boolean = false,
): ModelResponseException {
    rethrowIfCoroutineCancellation(t)
    val text = listOfNotNull(t.message, t.cause?.message).joinToString(" ").lowercase()
    val error = when {
        isNetworkFailure -> PracticeLensError.OFFLINE
        "app check" in text -> PracticeLensError.APP_CHECK_REJECTED
        "quota" in text || "resource_exhausted" in text -> PracticeLensError.QUOTA_EXCEEDED
        "timeout" in text || "deadline" in text -> PracticeLensError.TIMEOUT
        "unavailable" in text || "internal" in text || "503" in text || "500" in text -> PracticeLensError.TEMPORARY_SERVICE_FAILURE
        "api key" in text || "firebaseapp" in text || "google-services" in text || "not configured" in text -> PracticeLensError.FIREBASE_NOT_CONFIGURED
        else -> PracticeLensError.UNKNOWN
    }
    return ModelResponseException(error, "$operation failed: $error")
}
