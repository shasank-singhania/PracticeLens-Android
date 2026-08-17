package app.practicelens.android.ocr

class OcrGuideFilter(
    @Suppress("unused") private val widthFraction: Float = 0.88f,
    @Suppress("unused") private val heightFraction: Float = 0.58f,
) {
    fun filter(lines: List<OcrTextLine>, imageWidth: Int, imageHeight: Int): OcrObservation {
        if (imageWidth <= 0 || imageHeight <= 0) return OcrObservation("")
        // The visible guide is only an aiming aid. PreviewView scaling and rotation mean
        // OCR coordinates cannot be filtered against that guide without an explicit transform.
        val selected = lines
            .asSequence()
            .filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
            .sortedWith(compareBy<OcrTextLine> { it.top }.thenBy { it.left })
            .toList()

        return OcrObservation(
            fullText = selected.joinToString("\n") { it.text.trim() },
            lines = selected,
        )
    }
}
