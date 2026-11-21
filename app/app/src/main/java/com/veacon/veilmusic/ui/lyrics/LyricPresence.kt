package com.veacon.veilmusic.ui.lyrics


internal data class TerminalLyricRelease(
    val firstLineIndex: Int,
    val vocalsEndMs: Long,
    val releaseMs: Long,
)

/** Calculated once per song from the same timings used by the rendered words. */
internal fun terminalLyricRelease(
    lines: List<LyricLine>,
    finalClusterStartIndex: Int,
    rushGradientMs: Long,
): TerminalLyricRelease? {
    if (finalClusterStartIndex !in lines.indices) return null
    val finalLines = lines.drop(finalClusterStartIndex).filter { !it.isStatic }
    if (finalLines.isEmpty()) return null
    val vocalsEndMs = finalLines.maxOf { line ->
        maxOf(line.mainEndMs, line.backgroundWords.maxOfOrNull { it.endMs } ?: line.mainEndMs)
    }
    var releaseMs = vocalsEndMs
    for (line in finalLines) {
        if (line.timing != LyricTiming.Word) continue
        for (word in line.words + line.backgroundWords) {
            val rushMs = if (LyricTimeline.containsSplitOnCharsScript(word.text)) 0L else rushGradientMs
            releaseMs = maxOf(releaseMs, word.endMs + LyricTimeline.KARAOKE_WORD_OFFSET_MS + rushMs)
        }
        for (wordIndex in line.words.indices) {
            for (timing in LyricTimeline.characterEmphasisTimings(
                line, wordIndex, LyricTimeline.KARAOKE_WORD_OFFSET_MS,
            )) {
                releaseMs = maxOf(releaseMs, timing.settleStartMs + timing.animationDurationMs)
            }
        }
    }
    return TerminalLyricRelease(finalClusterStartIndex, vocalsEndMs, releaseMs)
}

/** Finish visual tails even if the player's position stops exactly at the final vocal end. */
internal fun terminalLyricTailPosition(
    playbackPositionMs: Long,
    tailStartPositionMs: Long,
    elapsedMs: Long,
    release: TerminalLyricRelease,
): Long {
    if (playbackPositionMs < release.vocalsEndMs) return playbackPositionMs
    val tailPositionMs = (tailStartPositionMs + elapsedMs.coerceAtLeast(0L)).coerceAtMost(release.releaseMs)
    return maxOf(playbackPositionMs, tailPositionMs)
}

/** A geometric retention boundary may move several pixels per frame, but its tint must not snap. */
internal fun advanceLyricPresence(current: Float, desired: Float, elapsedMs: Long): Float {
    val from = current.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val to = desired.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    // The live karaoke/line-sync envelope already owns activation timing.
    if (to >= from) return to
    val maximumFall = elapsedMs.coerceIn(0L, 64L) / 300f
    return maxOf(to, from - maximumFall)
}
