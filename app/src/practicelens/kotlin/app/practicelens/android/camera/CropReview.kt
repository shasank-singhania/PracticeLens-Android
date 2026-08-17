package app.practicelens.android.camera

import kotlin.math.roundToInt

data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
    }
}

data class PixelCropRect(val left: Int, val top: Int, val width: Int, val height: Int)

object CropReviewGeometry {
    private const val MinCropFraction = 0.12f

    fun initialGuide(): NormalizedCropRect = NormalizedCropRect(0.03f, 0.14f, 0.97f, 0.86f)

    fun fullImage(): NormalizedCropRect = NormalizedCropRect(0f, 0f, 1f, 1f)

    fun clamp(rect: NormalizedCropRect): NormalizedCropRect {
        val width = (rect.right - rect.left).coerceAtLeast(MinCropFraction).coerceAtMost(1f)
        val height = (rect.bottom - rect.top).coerceAtLeast(MinCropFraction).coerceAtMost(1f)
        val left = rect.left.coerceIn(0f, 1f - width)
        val top = rect.top.coerceIn(0f, 1f - height)
        return NormalizedCropRect(left, top, left + width, top + height)
    }

    fun move(rect: NormalizedCropRect, dx: Float, dy: Float): NormalizedCropRect {
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        val left = (rect.left + dx).coerceIn(0f, 1f - width)
        val top = (rect.top + dy).coerceIn(0f, 1f - height)
        return clamp(NormalizedCropRect(left, top, left + width, top + height))
    }

    fun resize(rect: NormalizedCropRect, left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f): NormalizedCropRect =
        clamp(
            NormalizedCropRect(
                left = (rect.left + left).coerceIn(0f, rect.right - MinCropFraction),
                top = (rect.top + top).coerceIn(0f, rect.bottom - MinCropFraction),
                right = (rect.right + right).coerceIn(rect.left + MinCropFraction, 1f),
                bottom = (rect.bottom + bottom).coerceIn(rect.top + MinCropFraction, 1f),
            ),
        )

    fun toPixels(rect: NormalizedCropRect, imageWidth: Int, imageHeight: Int): PixelCropRect {
        require(imageWidth > 0 && imageHeight > 0)
        val clamped = clamp(rect)
        val left = (clamped.left * imageWidth).roundToInt().coerceIn(0, imageWidth - 1)
        val top = (clamped.top * imageHeight).roundToInt().coerceIn(0, imageHeight - 1)
        val right = (clamped.right * imageWidth).roundToInt().coerceIn(left + 1, imageWidth)
        val bottom = (clamped.bottom * imageHeight).roundToInt().coerceIn(top + 1, imageHeight)
        return PixelCropRect(left, top, right - left, bottom - top)
    }

    fun rotatedDimensions(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (rotationDegrees.floorMod360() in setOf(90, 270)) height to width else width to height

    private fun Int.floorMod360(): Int = ((this % 360) + 360) % 360
}
