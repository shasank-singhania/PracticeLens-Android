package app.practicelens.android.ocr

import kotlin.math.abs

data class OcrExtractionResult(
    val observation: OcrObservation,
    val confidence: Double,
    val optionCount: Int,
    val rejectionReason: String? = null,
    val warnings: List<String> = emptyList(),
    val region: OcrQuestionRegion? = null,
)

data class OcrQuestionRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val lineCount: Int,
    val reason: String,
)

private data class VisualRegionCandidate(
    val lines: List<OcrTextLine>,
    val score: Double,
    val region: OcrQuestionRegion,
)

private data class QuizCardCandidate(
    val promptLines: List<OcrTextLine>,
    val optionGroups: List<List<OcrTextLine>>,
    val score: Double,
    val region: OcrQuestionRegion,
    val hadFeedback: Boolean,
)

private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerY: Int get() = top + height / 2
}

data class OptionAnchor(
    val lineIndex: Int,
    val label: String,
    val ordinal: Int,
    val numeric: Boolean,
    val text: String,
    val kind: AnchorKind,
    val anchorLeft: Int,
    val textLeft: Int,
)

enum class AnchorKind { TEXT_LABEL, BULLET, OCR_CIRCLE }

interface OptionAnchorDetector {
    fun detect(lines: List<OcrTextLine>): List<OptionAnchor>
}

class TextAndGeometryOptionAnchorDetector : OptionAnchorDetector {
    private val labelPattern = Regex("""^\s*[\(\[]?\s*([A-Ha-h1-8]|\d{1,2})\s*[\)\].:\-]\s+(.+?)\s*$""")
    private val bulletPattern = Regex("""^\s*([•◦○●▪□☐])\s+(.+?)\s*$""")
    private val circleLikePattern = Regex("""^\s*([O0])\s+(.+?)\s*$""")

    override fun detect(lines: List<OcrTextLine>): List<OptionAnchor> {
        val anchors = lines.mapIndexedNotNull { index, line ->
            textualAnchor(index, line) ?: bulletAnchor(index, line)
        }.toMutableList()
        val circleCandidates = lines.mapIndexedNotNull { index, line -> circleAnchor(index, line) }
        val grouped = circleCandidates.groupBy { it.anchorLeft / 16 }
        grouped.values.filter { it.size >= 2 }.forEach { consistent ->
            consistent.forEachIndexed { ordinal, anchor ->
                anchors += anchor.copy(label = ('A' + ordinal).toString(), ordinal = ordinal)
            }
        }
        return anchors.sortedWith(compareBy<OptionAnchor> { it.lineIndex }.thenBy { it.textLeft })
    }

    private fun textualAnchor(index: Int, line: OcrTextLine): OptionAnchor? {
        val match = labelPattern.matchEntire(line.text) ?: return null
        val raw = match.groupValues[1].uppercase()
        val numeric = raw.first().isDigit()
        val ordinal = if (numeric) raw.toIntOrNull()?.minus(1) ?: return null else raw.first() - 'A'
        if (ordinal !in 0..7) return null
        return OptionAnchor(index, raw, ordinal, numeric, match.groupValues[2].trim(), AnchorKind.TEXT_LABEL, line.left, textLeft(line))
    }

    private fun bulletAnchor(index: Int, line: OcrTextLine): OptionAnchor? {
        val match = bulletPattern.matchEntire(line.text) ?: return null
        return OptionAnchor(index, "", index, false, match.groupValues[2].trim(), AnchorKind.BULLET, line.left, textLeft(line))
    }

    private fun circleAnchor(index: Int, line: OcrTextLine): OptionAnchor? {
        val match = circleLikePattern.matchEntire(line.text) ?: return null
        return OptionAnchor(index, "", index, false, match.groupValues[2].trim(), AnchorKind.OCR_CIRCLE, line.left, textLeft(line))
    }

    private fun textLeft(line: OcrTextLine): Int =
        line.elements.firstOrNull { it.text.isNotBlank() }?.left ?: line.left
}

class OcrMcqExtractor(
    private val anchorDetector: OptionAnchorDetector = TextAndGeometryOptionAnchorDetector(),
) {
    private val questionNumberPattern = Regex("""(?i)^\s*(?:question\s*|q\s*)?\d{1,3}\s*[\).:\-].*""")
    private val questionLabelPattern = Regex("""(?i)^\s*question\s*\d{1,3}\s*$""")
    private val feedbackPattern = Regex("""(?i)^\s*(incorrect|correct|explanation|solution|answer)\b.*""")
    private val chromePattern = Regex("""(?i)\b(calendar|grades|share|next|previous|submit|timer|course|menu|logout|questions)\b""")

    fun extract(observation: OcrObservation): OcrExtractionResult? {
        val allLines = orderedLines(observation).filterNot { chromePattern.containsMatchIn(it.text) }
        if (allLines.size < 3) return null
        questionCardExtraction(allLines, observation)?.let { return it }
        val visualRegion = visualQuestionRegion(allLines, observation)
        val lines = visualRegion?.lines ?: allLines
        val anchors = anchorDetector.detect(lines)
        if (anchors.size < 2) return null
        val best = bestSequentialRun(anchors) ?: return null
        if (best.size < 2) return null

        val firstOption = best.first().lineIndex
        if (firstOption <= 0) return null
        val questionStart = questionStart(lines, firstOption - 1)
        val selected = buildSelectedLines(lines, questionStart, best)
        val confidence = confidence(best, selected)
        val warnings = mutableListOf<String>()
        if (selected.any { hasLikelyMathOrUnsupportedSymbols(it.text) }) {
            warnings += "Equations and symbols need review; ML Kit Latin OCR may not read formulas reliably."
        }
        visualRegion?.region?.let { region ->
            warnings += "Detected question region: ${region.lineCount} OCR lines inside ${region.left},${region.top}-${region.right},${region.bottom}."
        }
        return OcrExtractionResult(
            observation = observation(selected, observation),
            confidence = (confidence + (visualRegion?.score ?: 0.0) * 0.08).coerceAtMost(1.0),
            optionCount = best.size,
            warnings = warnings,
            region = visualRegion?.region,
        )
    }

    fun rejectionReason(observation: OcrObservation): String {
        val lines = orderedLines(observation).filterNot { chromePattern.containsMatchIn(it.text) }
        val anchors = anchorDetector.detect(lines)
        return when {
            lines.isEmpty() -> "No readable text."
            anchors.isEmpty() -> "No option anchors detected."
            anchors.size < 2 -> "Fewer than two option anchors detected."
            bestSequentialRun(anchors) == null -> "Option anchors are not sequential."
            else -> "Could not isolate a compact question and option block."
        }
    }

    private fun bestSequentialRun(anchors: List<OptionAnchor>): List<OptionAnchor>? {
        var best = emptyList<OptionAnchor>()
        for (start in anchors.indices) {
            val run = mutableListOf(anchors[start])
            for (nextIndex in start + 1 until anchors.size) {
                val next = anchors[nextIndex]
                val previous = run.last()
                val sequential = when {
                    previous.kind != AnchorKind.TEXT_LABEL || next.kind != AnchorKind.TEXT_LABEL -> sameAnchorColumn(previous, next)
                    next.numeric != previous.numeric -> false
                    else -> next.ordinal == previous.ordinal + 1
                }
                if (!sequential) break
                if (hasLargeGap(previousLine = previous, nextLine = next)) break
                run += next
                if (run.size == 8) break
            }
            if (run.size > best.size) best = run
        }
        return best.takeIf { it.size >= 2 }
    }

    private fun questionCardExtraction(lines: List<OcrTextLine>, observation: OcrObservation): OcrExtractionResult? {
        if (lines.count(::positioned) < 4) return null
        val labels = lines.withIndex().filter { questionLabelPattern.matches(it.value.text) && positioned(it.value) }
        if (labels.isEmpty()) return null
        val candidates = labels.mapIndexedNotNull { labelPosition, indexedLabel ->
            val nextLabelIndex = labels.getOrNull(labelPosition + 1)?.index ?: lines.size
            quizCardCandidate(lines, indexedLabel.index, nextLabelIndex, observation)
        }
        val best = candidates
            .maxWithOrNull(
                compareBy<QuizCardCandidate> { !it.hadFeedback }
                    .thenBy { it.optionGroups.size }
                    .thenBy { it.score },
            )
            ?: return null
        val selected = mutableListOf<OcrTextLine>()
        selected += best.promptLines
        best.optionGroups.forEachIndexed { index, group ->
            val first = group.first()
            val optionText = group.joinToString("\n") { it.text.trim() }
            selected += first.copy(text = "${'A' + index}. $optionText")
        }
        val warnings = mutableListOf(
            "Detected quiz-card layout: ${best.region.lineCount} OCR lines inside ${best.region.left},${best.region.top}-${best.region.right},${best.region.bottom}.",
            "Options were inferred from radio-row layout; review labels before confirming.",
        )
        if (best.hadFeedback) warnings += "Excluded feedback/explanation text below the option rows."
        return OcrExtractionResult(
            observation = observation(selected, observation),
            confidence = best.score.coerceIn(0.0, 0.92),
            optionCount = best.optionGroups.size,
            warnings = warnings,
            region = best.region,
        )
    }

    private fun quizCardCandidate(
        lines: List<OcrTextLine>,
        labelIndex: Int,
        nextLabelIndex: Int,
        observation: OcrObservation,
    ): QuizCardCandidate? {
        val label = lines[labelIndex]
        val scoped = ((labelIndex + 1) until nextLabelIndex)
            .map { it to lines[it] }
            .filter { (_, line) -> line.text.isNotBlank() && !chromePattern.containsMatchIn(line.text) }
        if (scoped.size < 3) return null
        val feedbackStart = scoped.indexOfFirst { (_, line) -> feedbackPattern.containsMatchIn(line.text) }
        val content = if (feedbackStart >= 0) scoped.take(feedbackStart) else scoped
        if (content.size < 3) return null
        val contentRightOfLabel = content.filter { (_, line) -> !positioned(line) || line.left >= label.right - 32 }
        if (contentRightOfLabel.size < 3) return null
        val cueIndex = contentRightOfLabel.indexOfLast { (_, line) -> line.text.contains('?') || questionNumberPattern.matches(line.text) }
        if (cueIndex >= 0 && cueIndex < contentRightOfLabel.lastIndex) {
            val prompt = contentRightOfLabel.take(cueIndex + 1).map { it.second }.filterNot { questionLabelPattern.matches(it.text) }
            val groups = radioOptionGroups(contentRightOfLabel.drop(cueIndex + 1).map { it.second })
            quizCardCandidateFromGroups(label, prompt, groups, feedbackStart, observation, lines)?.let { return it }
        }

        val starts = (1 until contentRightOfLabel.size).mapNotNull { start ->
            val prompt = contentRightOfLabel.take(start).map { it.second }.filterNot { questionLabelPattern.matches(it.text) }
            val optionLines = contentRightOfLabel.drop(start).map { it.second }
            val groups = radioOptionGroups(optionLines)
            if (prompt.isEmpty() || groups.size < 2) return@mapNotNull null
            val promptCue = prompt.any { it.text.contains('?') || questionNumberPattern.matches(it.text) }
            if (!promptCue) return@mapNotNull null
            quizCardCandidateFromGroups(label, prompt, groups, feedbackStart, observation, lines)
        }
        return starts
            .maxWithOrNull(compareBy<QuizCardCandidate> { it.optionGroups.size }.thenBy { it.score })
            ?: starts.maxByOrNull { it.score }
    }

    private fun quizCardCandidateFromGroups(
        label: OcrTextLine,
        prompt: List<OcrTextLine>,
        groups: List<List<OcrTextLine>>,
        feedbackStart: Int,
        observation: OcrObservation,
        lines: List<OcrTextLine>,
    ): QuizCardCandidate? {
        if (prompt.isEmpty() || groups.size < 2) return null
        val optionFirstLines = groups.map { it.first() }
        val optionLefts = optionFirstLines.map { it.left }
        val leftSpread = (optionLefts.maxOrNull() ?: 0) - (optionLefts.minOrNull() ?: 0)
        val optionSpacingScore = if (leftSpread <= 56) 0.20 else 0.08
        val countScore = when (groups.size) {
            2 -> 0.14
            3, 4, 5 -> 0.22
            else -> 0.10
        }
        val regionLines = listOf(label) + prompt + groups.flatten()
        val bounds = bounds(regionLines) ?: return null
        val imageHeight = observation.height.takeIf { it > 0 } ?: lines.maxOfOrNull { it.bottom }?.coerceAtLeast(1) ?: 1
        val imageWidth = observation.width.takeIf { it > 0 } ?: lines.maxOfOrNull { it.right }?.coerceAtLeast(1) ?: 1
        val compactHeightScore = (1.0 - bounds.height.toDouble() / imageHeight.toDouble()).coerceIn(0.0, 0.16)
        val compactWidthScore = if (bounds.width <= imageWidth * 0.82) 0.12 else 0.04
        val centerDistance = abs(bounds.centerY - imageHeight / 2).toDouble() / maxOf(imageHeight / 2, 1)
        val centerScore = (0.10 * (1.0 - centerDistance)).coerceIn(0.0, 0.10)
        val feedbackPenalty = if (feedbackStart >= 0) 0.35 else 0.0
        return QuizCardCandidate(
            promptLines = prompt,
            optionGroups = groups.take(8),
            score = 0.22 + countScore + optionSpacingScore + compactHeightScore + compactWidthScore + centerScore - feedbackPenalty,
            region = OcrQuestionRegion(bounds.left, bounds.top, bounds.right, bounds.bottom, regionLines.size, "quiz-card-radio-layout"),
            hadFeedback = feedbackStart >= 0,
        )
    }

    private fun radioOptionGroups(optionLines: List<OcrTextLine>): List<List<OcrTextLine>> {
        val usable = optionLines
            .filterNot { questionLabelPattern.matches(it.text) || feedbackPattern.containsMatchIn(it.text) }
            .filterNot { it.text.trim().equals("x", ignoreCase = true) }
        if (usable.size < 2) return emptyList()
        val groups = mutableListOf<MutableList<OcrTextLine>>()
        usable.forEach { line ->
            val previous = groups.lastOrNull()?.lastOrNull()
            val lineHeight = maxOf(line.bottom - line.top, previous?.let { it.bottom - it.top } ?: 1, 1)
            val gap = if (previous != null && positioned(previous) && positioned(line)) line.top - previous.bottom else Int.MAX_VALUE
            val sameColumn = previous == null || !positioned(previous) || !positioned(line) || abs(line.left - previous.left) <= 48
            val wrapsPrevious = previous != null && sameColumn && gap >= -lineHeight && gap <= 4
            if (wrapsPrevious) {
                groups.last() += line
            } else {
                groups += mutableListOf(line)
            }
        }
        return groups.filter { group -> group.joinToString(" ") { it.text }.trim().length >= 2 }.take(8)
    }

    private fun visualQuestionRegion(lines: List<OcrTextLine>, observation: OcrObservation): VisualRegionCandidate? {
        if (lines.count(::positioned) < 3) return null
        val anchors = anchorDetector.detect(lines)
        if (anchors.size < 2) return null
        val candidates = sequentialRuns(anchors).mapNotNull { run -> regionCandidate(lines, run, observation) }
        return candidates.maxByOrNull { it.score }?.takeIf { it.score >= 0.48 }
    }

    private fun sequentialRuns(anchors: List<OptionAnchor>): List<List<OptionAnchor>> {
        val runs = mutableListOf<List<OptionAnchor>>()
        for (start in anchors.indices) {
            val run = mutableListOf(anchors[start])
            for (nextIndex in start + 1 until anchors.size) {
                val next = anchors[nextIndex]
                val previous = run.last()
                val sequential = when {
                    previous.kind != AnchorKind.TEXT_LABEL || next.kind != AnchorKind.TEXT_LABEL -> sameAnchorColumn(previous, next)
                    next.numeric != previous.numeric -> false
                    else -> next.ordinal == previous.ordinal + 1
                }
                if (!sequential || hasLargeGap(previous, next)) break
                run += next
                if (run.size == 8) break
            }
            if (run.size >= 2) runs += run.toList()
        }
        return runs
    }

    private fun regionCandidate(lines: List<OcrTextLine>, anchors: List<OptionAnchor>, observation: OcrObservation): VisualRegionCandidate? {
        val firstOption = anchors.first().lineIndex
        if (firstOption <= 0) return null
        val questionStart = questionStart(lines, firstOption - 1)
        val optionEnd = optionEnd(lines, anchors)
        if (optionEnd <= questionStart) return null
        val regionLines = lines.subList(questionStart, optionEnd + 1)
        if (regionLines.size < anchors.size + 1) return null
        val bounds = bounds(regionLines) ?: return null
        val imageHeight = observation.height.takeIf { it > 0 } ?: lines.maxOfOrNull { it.bottom }?.coerceAtLeast(1) ?: 1
        val imageWidth = observation.width.takeIf { it > 0 } ?: lines.maxOfOrNull { it.right }?.coerceAtLeast(1) ?: 1
        val optionBounds = bounds(anchors.map { lines[it.lineIndex] }) ?: bounds
        val hasQuestionCue = regionLines.take(firstOption - questionStart).any { it.text.contains('?') || questionNumberPattern.matches(it.text) }
        val columnScore = if (anchors.zipWithNext().all { sameAnchorColumn(it.first, it.second) }) 0.22 else 0.10
        val compactHeightScore = (1.0 - bounds.height.toDouble() / imageHeight.toDouble()).coerceIn(0.0, 0.22)
        val compactWidthScore = if (bounds.width <= imageWidth * 0.96) 0.12 else 0.0
        val optionShapeScore = if (optionBounds.height > 0 && optionBounds.width > 0) 0.14 else 0.0
        val cueScore = if (hasQuestionCue) 0.18 else 0.06
        val centerDistance = abs(bounds.centerY - imageHeight / 2).toDouble() / maxOf(imageHeight / 2, 1)
        val centerScore = (0.14 * (1.0 - centerDistance)).coerceIn(0.0, 0.14)
        val score = columnScore + compactHeightScore + compactWidthScore + optionShapeScore + cueScore + centerScore
        return VisualRegionCandidate(
            lines = regionLines,
            score = score,
            region = OcrQuestionRegion(bounds.left, bounds.top, bounds.right, bounds.bottom, regionLines.size, "visual-anchor-cluster"),
        )
    }

    private fun optionEnd(lines: List<OcrTextLine>, anchors: List<OptionAnchor>): Int {
        var end = anchors.last().lineIndex
        anchors.forEachIndexed { index, anchor ->
            var cursor = anchor.lineIndex + 1
            var continuationCount = 0
            val nextAnchorIndex = anchors.getOrNull(index + 1)?.lineIndex ?: lines.size
            while (cursor < nextAnchorIndex && continuationCount < 4) {
                val candidate = lines[cursor]
                if (candidate.text.contains('?') || questionNumberPattern.matches(candidate.text)) break
                if (anchorDetector.detect(listOf(candidate)).isNotEmpty()) break
                if (!isOptionContinuation(anchor, candidate)) break
                end = maxOf(end, cursor)
                continuationCount++
                cursor++
            }
        }
        return end
    }

    private fun bounds(lines: List<OcrTextLine>): Bounds? {
        val positioned = lines.filter(::positioned)
        if (positioned.isEmpty()) return null
        return Bounds(
            left = positioned.minOf { it.left },
            top = positioned.minOf { it.top },
            right = positioned.maxOf { it.right },
            bottom = positioned.maxOf { it.bottom },
        )
    }

    private fun buildSelectedLines(lines: List<OcrTextLine>, questionStart: Int, anchors: List<OptionAnchor>): List<OcrTextLine> {
        val selected = mutableListOf<OcrTextLine>()
        selected += lines.subList(questionStart, anchors.first().lineIndex)
        anchors.forEachIndexed { index, anchor ->
            val label = anchor.label.ifBlank { ('A' + index).toString() }
            val optionLines = mutableListOf(lines[anchor.lineIndex].copy(text = "$label. ${anchor.text}"))
            val nextAnchorIndex = anchors.getOrNull(index + 1)?.lineIndex ?: lines.size
            var continuationCount = 0
            var cursor = anchor.lineIndex + 1
            while (cursor < nextAnchorIndex && continuationCount < 4) {
                val candidate = lines[cursor]
                if (candidate.text.contains('?') || questionNumberPattern.matches(candidate.text)) break
                if (!isOptionContinuation(anchor, candidate)) break
                optionLines += candidate
                continuationCount++
                cursor++
            }
            selected += optionLines
        }
        return selected
    }

    private fun questionStart(lines: List<OcrTextLine>, questionEnd: Int): Int {
        var start = questionEnd
        var included = 1
        while (start > 0 && included < 8) {
            val previous = lines[start - 1]
            val current = lines[start]
            if (anchorDetector.detect(listOf(previous)).isNotEmpty() || hasLargeGap(previous, current)) break
            if (chromePattern.containsMatchIn(previous.text)) break
            start--
            included++
        }
        val numbered = (start..questionEnd).firstOrNull { questionNumberPattern.matches(lines[it].text) }
        return numbered ?: start
    }

    private fun isOptionContinuation(anchor: OptionAnchor, candidate: OcrTextLine): Boolean {
        if (!positioned(candidate)) return true
        val lineHeight = maxOf(candidate.bottom - candidate.top, 1)
        val textColumn = if (anchor.textLeft > anchor.anchorLeft) anchor.textLeft else anchor.anchorLeft + lineHeight
        return candidate.left >= textColumn - 24
    }

    private fun confidence(anchors: List<OptionAnchor>, selected: List<OcrTextLine>): Double {
        val labelScore = anchors.count { it.kind == AnchorKind.TEXT_LABEL } / anchors.size.toDouble()
        val geometryScore = if (anchors.zipWithNext().all { sameAnchorColumn(it.first, it.second) }) 0.2 else 0.0
        val compactScore = if (selected.size <= anchors.size + 8) 0.2 else 0.0
        return (0.45 + labelScore * 0.3 + geometryScore + compactScore).coerceAtMost(1.0)
    }

    private fun sameAnchorColumn(a: OptionAnchor, b: OptionAnchor): Boolean =
        !positionedColumn(a, b) || abs(a.anchorLeft - b.anchorLeft) <= 32

    private fun positionedColumn(a: OptionAnchor, b: OptionAnchor): Boolean =
        a.anchorLeft != 0 || b.anchorLeft != 0

    private fun hasLargeGap(previousLine: OptionAnchor, nextLine: OptionAnchor): Boolean =
        nextLine.lineIndex - previousLine.lineIndex > 6

    private fun hasLargeGap(previous: OcrTextLine, next: OcrTextLine): Boolean {
        if (!positioned(previous) || !positioned(next)) return false
        val lineHeight = maxOf(previous.bottom - previous.top, next.bottom - next.top, 1)
        return next.top - previous.bottom > maxOf(28, lineHeight * 3)
    }

    private fun orderedLines(observation: OcrObservation): List<OcrTextLine> {
        val blockLines = observation.blocks.flatMap { it.lines }
        val readable = (blockLines.ifEmpty { observation.lines }).filter { it.text.isNotBlank() }
        return if (readable.count(::positioned) >= 2) {
            readable.sortedWith(compareBy<OcrTextLine> { it.top }.thenBy { it.left })
        } else {
            observation.fullText.lines().filter { it.isNotBlank() }.map { OcrTextLine(it.trim()) }
        }
    }

    private fun hasLikelyMathOrUnsupportedSymbols(text: String): Boolean =
        text.any { it in listOf('∑', '√', 'π', 'θ', '≤', '≥', '≠', '∞', '²', '³', '÷', '×') }

    private fun positioned(line: OcrTextLine): Boolean = line.right > line.left && line.bottom > line.top

    private fun observation(lines: List<OcrTextLine>, source: OcrObservation) = OcrObservation(
        fullText = lines.joinToString("\n") { it.text.trim() },
        lines = lines,
        blocks = source.blocks,
        rotationDegrees = source.rotationDegrees,
        width = source.width,
        height = source.height,
        durationMs = source.durationMs,
    )
}
