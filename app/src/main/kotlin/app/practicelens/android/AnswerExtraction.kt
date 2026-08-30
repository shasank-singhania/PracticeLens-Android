package app.practicelens.android

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class AnswerEntry(
    val questionOrdinal: Int? = null,
    val optionPosition: Int? = null,
    val marker: String? = null,
    val geminiOptionText: String? = null,
    val ocrSuggestion: String? = null,
    val ocrSimilarityScore: Double? = null,
    val ocrLabel: OcrSuggestionLabel? = null,
    val rawGeminiLine: String,
)

enum class OcrSuggestionLabel {
    APPROXIMATE_MATCH,
    APPROXIMATE_OPTION_TEXT,
    POSSIBLE_MATCH,
}

data class PresentationResult(
    val answers: List<AnswerEntry>,
    val rawGeminiText: String,
    val finishReason: String?,
    val tokenMetadata: GeminiTokenMetadata,
    val notice: String? = null,
)

data class OcrResult(
    val blocks: List<OcrBlock> = emptyList(),
    val candidates: List<OcrCandidate> = emptyList(),
)

data class OcrBlock(
    val text: String,
    val lines: List<OcrLine>,
    val bounds: TextBounds? = null,
    val cornerPoints: List<TextPoint> = emptyList(),
)

data class OcrLine(
    val text: String,
    val elements: List<OcrElement> = emptyList(),
    val bounds: TextBounds? = null,
    val cornerPoints: List<TextPoint> = emptyList(),
    val confidence: Float? = null,
    val readingOrder: Int,
)

data class OcrElement(
    val text: String,
    val symbols: List<OcrSymbol> = emptyList(),
    val bounds: TextBounds? = null,
    val cornerPoints: List<TextPoint> = emptyList(),
    val confidence: Float? = null,
)

data class OcrSymbol(
    val text: String,
    val bounds: TextBounds? = null,
    val cornerPoints: List<TextPoint> = emptyList(),
    val confidence: Float? = null,
)

data class OcrCandidate(
    val text: String,
    val marker: String? = null,
    val position: Int? = null,
    val confidence: Float? = null,
    val bounds: TextBounds? = null,
    val readingOrder: Int,
)

data class TextBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class TextPoint(val x: Int, val y: Int)

object AnswerExtractor {
    fun extract(rawGeminiText: String): List<AnswerEntry> =
        rawGeminiText
            .lines()
            .filter { it.isNotBlank() }
            .map { parseLine(it) }

    private fun parseLine(line: String): AnswerEntry {
        val question = Regex("""(?i)\b(?:q|question)\s*#?\s*(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""^\s*(\d+)[.)\]:-]\s+""").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val position = Regex("""(?i)\bposition\s+(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val marker = parseMarker(line)
        val optionText = parseOptionText(line)
        return AnswerEntry(
            questionOrdinal = question,
            optionPosition = position,
            marker = marker,
            geminiOptionText = optionText,
            rawGeminiLine = line,
        )
    }

    private fun parseMarker(line: String): String? {
        Regex("""(?i)\bmarker\s+([^|]+)""").find(line)?.groupValues?.getOrNull(1)?.trim()?.let {
            return it.takeUnless { value -> value.equals("NONE", ignoreCase = true) }
        }
        Regex("""(?i)\boption\s+([^\s|:.-]+)""").find(line)?.groupValues?.getOrNull(1)?.trim()?.let { return it }
        Regex("""^\s*(?:Q(?:uestion)?\s*#?\s*\d+\s*[:.)-]\s*)?([^\s|:.-]+)\s*[|.-]\s+""").find(line)
            ?.groupValues?.getOrNull(1)?.trim()?.let { return it }
        return null
    }

    private fun parseOptionText(line: String): String? {
        Regex("""\|\s*marker\s+[^|]+\|\s*(.+)$""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)?.trim()?.let {
            return it.takeIf(String::isNotBlank)
        }
        Regex("""\|\s*(.+)$""").find(line)?.groupValues?.getOrNull(1)?.trim()?.let { return it.takeIf(String::isNotBlank) }
        Regex("""(?i)\boption\s+[^\s|:.-]+\s*[-:]\s*(.+)$""").find(line)?.groupValues?.getOrNull(1)?.trim()?.let {
            return it.takeIf(String::isNotBlank)
        }
        Regex("""^\s*(?:\d+[.)]\s*)?[^\s|:.-]+\s*[.)-]\s+(.+)$""").find(line)?.groupValues?.getOrNull(1)?.trim()?.let {
            return it.takeIf(String::isNotBlank)
        }
        return null
    }
}

object OcrAnswerMatcher {
    fun enrich(answers: List<AnswerEntry>, ocrResult: OcrResult): List<AnswerEntry> {
        if (answers.isEmpty() || ocrResult.candidates.isEmpty()) return answers
        val used = mutableSetOf<Int>()
        return answers.map { answer ->
            val ranked = ocrResult.candidates
                .mapIndexed { index, candidate -> index to score(answer, candidate) }
                .filterNot { it.first in used }
                .sortedByDescending { it.second }
            val best = ranked.firstOrNull() ?: return@map answer
            val runnerUp = ranked.getOrNull(1)?.second ?: 0.0
            val candidate = ocrResult.candidates[best.first]
            val label = when {
                best.second >= 0.72 && answer.geminiOptionText.isNullOrBlank() -> OcrSuggestionLabel.APPROXIMATE_OPTION_TEXT
                best.second >= 0.72 -> OcrSuggestionLabel.APPROXIMATE_MATCH
                best.second >= 0.40 && best.second - runnerUp >= 0.08 -> OcrSuggestionLabel.POSSIBLE_MATCH
                else -> null
            }
            if (label == null) {
                answer
            } else {
                used += best.first
                answer.copy(
                    ocrSuggestion = candidate.text,
                    ocrSimilarityScore = best.second,
                    ocrLabel = label,
                )
            }
        }
    }

    private fun score(answer: AnswerEntry, candidate: OcrCandidate): Double {
        val targetText = answer.geminiOptionText.orEmpty()
        val textScore = if (targetText.isBlank()) 0.0 else blendedTextScore(targetText, candidate.text)
        val markerScore = markerSimilarity(answer.marker, candidate.marker)
        val positionScore = if (answer.optionPosition != null && candidate.position != null) {
            if (answer.optionPosition == candidate.position) 1.0 else 0.0
        } else {
            0.0
        }
        val markerPrefixScore = if (targetText.isBlank() && !answer.marker.isNullOrBlank()) {
            markerSimilarity(answer.marker, firstToken(candidate.text))
        } else {
            0.0
        }
        val confidenceScore = candidate.confidence?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.5
        val orderScore = 1.0 / (1.0 + candidate.readingOrder.coerceAtLeast(0) / 20.0)
        val base = if (targetText.isBlank()) markerPrefixScore else textScore
        return (base * 0.52 + markerScore * 0.16 + positionScore * 0.14 + confidenceScore * 0.10 + orderScore * 0.08)
            .coerceIn(0.0, 1.0)
    }

    private fun blendedTextScore(a: String, b: String): Double {
        val left = NormalForms.from(a)
        val right = NormalForms.from(b)
        val edit = editSimilarity(left.punctuationTolerant, right.punctuationTolerant)
        val grams = jaccard(ngrams(left.punctuationTolerant), ngrams(right.punctuationTolerant))
        val tokens = jaccard(left.tokens, right.tokens)
        return edit * 0.45 + grams * 0.35 + tokens * 0.20
    }

    private fun markerSimilarity(left: String?, right: String?): Double {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return 0.0
        val a = NormalForms.from(left).punctuationTolerant
        val b = NormalForms.from(right).punctuationTolerant
        return when {
            a == b -> 1.0
            a.contains(b) || b.contains(a) -> 0.75
            else -> editSimilarity(a, b)
        }
    }

    private fun firstToken(text: String): String? = text.trim().split(Regex("""\s+""")).firstOrNull()

    private data class NormalForms(
        val nfkc: String,
        val caseFolded: String,
        val whitespaceCollapsed: String,
        val punctuationTolerant: String,
        val tokens: Set<String>,
    ) {
        companion object {
            fun from(value: String): NormalForms {
                val nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC)
                val caseFolded = nfkc.lowercase(Locale.ROOT)
                val whitespaceCollapsed = caseFolded.replace(Regex("""\s+"""), " ").trim()
                val punctuationTolerant = whitespaceCollapsed.replace(Regex("""[\p{Punct}\p{So}\p{Sk}]+"""), " ").replace(Regex("""\s+"""), " ").trim()
                val tokens = punctuationTolerant.split(" ").filter { it.isNotBlank() }.toSet()
                return NormalForms(nfkc, caseFolded, whitespaceCollapsed, punctuationTolerant, tokens)
            }
        }
    }

    private fun ngrams(value: String): Set<String> {
        val compact = value.replace(" ", "")
        if (compact.isEmpty()) return emptySet()
        if (compact.length <= 3) return setOf(compact)
        return compact.windowed(3).toSet()
    }

    private fun <T> jaccard(left: Set<T>, right: Set<T>): Double {
        if (left.isEmpty() && right.isEmpty()) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size.toDouble()
    }

    private fun editSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            }
            for (j in previous.indices) previous[j] = current[j]
        }
        return 1.0 - previous[b.length].toDouble() / max(a.length, b.length).toDouble()
    }
}
