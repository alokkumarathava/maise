package com.danemadsen.maise.tts

import kotlin.math.sqrt

/**
 * Diagnostic metrics and silence analysis result for a single PCM audio chunk.
 */
data class PcmChunkMetrics(
    val sampleCount: Int,
    val audioDurationMs: Long,
    val leadingSilenceMs: Long,
    val trailingSilenceMs: Long,
    val internalSilenceRegionCount: Int,
    val totalInternalSilenceMs: Long,
    val inferenceTimeMs: Long,
    val rtf: Float
)

/**
 * Utility for calculating short-window RMS silence metrics and conservative
 * boundary-silence normalization for 16-bit 24kHz mono PCM audio.
 */
object PcmSilenceProcessor {

    private const val WINDOW_SIZE = 240 // 10ms at 24000 Hz
    private const val RMS_SILENCE_THRESHOLD = 250.0f // ~ -42 dBFS threshold for 16-bit PCM
    private const val MIN_SILENCE_WINDOWS = 3 // At least 30ms continuous to count as internal silence

    /**
     * Whether boundary silence trimming is enabled.
     * Default is false per requirements ("Disable trimming by default until the measured thresholds are validated").
     */
    @Volatile
    var enableTrimming: Boolean = false

    var maxLeadingSilencePaddingMs: Long = 20L
    var maxTrailingSilencePaddingMs: Long = 40L
    var fadeDurationMs: Long = 5L

    fun analyzeChunk(pcm: ShortArray, inferenceTimeMs: Long, sampleRate: Int = SAMPLE_RATE): PcmChunkMetrics {
        if (pcm.isEmpty()) {
            return PcmChunkMetrics(0, 0L, 0L, 0L, 0, 0L, inferenceTimeMs, 0.0f)
        }

        val totalDurationMs = (pcm.size * 1000L) / sampleRate
        val rtf = if (totalDurationMs > 0) inferenceTimeMs.toFloat() / totalDurationMs.toFloat() else 0f

        val windowCount = pcm.size / WINDOW_SIZE
        if (windowCount == 0) {
            return PcmChunkMetrics(pcm.size, totalDurationMs, 0L, 0L, 0, 0L, inferenceTimeMs, rtf)
        }

        val windowRms = FloatArray(windowCount)
        for (i in 0 until windowCount) {
            var sum = 0.0
            val start = i * WINDOW_SIZE
            for (j in start until start + WINDOW_SIZE) {
                val s = pcm[j].toDouble()
                sum += s * s
            }
            windowRms[i] = sqrt(sum / WINDOW_SIZE).toFloat()
        }

        // Leading silence
        var leadingWindows = 0
        while (leadingWindows < windowCount && windowRms[leadingWindows] < RMS_SILENCE_THRESHOLD) {
            leadingWindows++
        }
        val leadingSilenceMs = (leadingWindows * WINDOW_SIZE * 1000L) / sampleRate

        // Trailing silence
        var trailingWindows = 0
        while (trailingWindows < windowCount && windowRms[windowCount - 1 - trailingWindows] < RMS_SILENCE_THRESHOLD) {
            trailingWindows++
        }
        val trailingSilenceMs = (trailingWindows * WINDOW_SIZE * 1000L) / sampleRate

        // Internal silence regions (between speech frames)
        var internalSilenceRegionCount = 0
        var totalInternalSilenceMs = 0L
        val firstSpeechIdx = leadingWindows
        val lastSpeechIdx = maxOf(firstSpeechIdx, windowCount - trailingWindows)

        var currentSilenceCount = 0
        for (i in firstSpeechIdx until lastSpeechIdx) {
            if (windowRms[i] < RMS_SILENCE_THRESHOLD) {
                currentSilenceCount++
            } else {
                if (currentSilenceCount >= MIN_SILENCE_WINDOWS) {
                    internalSilenceRegionCount++
                    totalInternalSilenceMs += (currentSilenceCount * WINDOW_SIZE * 1000L) / sampleRate
                }
                currentSilenceCount = 0
            }
        }
        if (currentSilenceCount >= MIN_SILENCE_WINDOWS) {
            internalSilenceRegionCount++
            totalInternalSilenceMs += (currentSilenceCount * WINDOW_SIZE * 1000L) / sampleRate
        }

        return PcmChunkMetrics(
            sampleCount = pcm.size,
            audioDurationMs = totalDurationMs,
            leadingSilenceMs = leadingSilenceMs,
            trailingSilenceMs = trailingSilenceMs,
            internalSilenceRegionCount = internalSilenceRegionCount,
            totalInternalSilenceMs = totalInternalSilenceMs,
            inferenceTimeMs = inferenceTimeMs,
            rtf = rtf
        )
    }

    /**
     * Normalizes boundary silence of [pcm] if [enableTrimming] is true.
     * Retains safety padding to avoid clipping consonants or breathing tails,
     * and applies short click-prevention fades.
     */
    fun processChunk(pcm: ShortArray, sampleRate: Int = SAMPLE_RATE): ShortArray {
        if (!enableTrimming || pcm.isEmpty()) return pcm

        val windowCount = pcm.size / WINDOW_SIZE
        if (windowCount == 0) return pcm

        val windowRms = FloatArray(windowCount)
        for (i in 0 until windowCount) {
            var sum = 0.0
            val start = i * WINDOW_SIZE
            for (j in start until start + WINDOW_SIZE) {
                val s = pcm[j].toDouble()
                sum += s * s
            }
            windowRms[i] = sqrt(sum / WINDOW_SIZE).toFloat()
        }

        var leadingWindows = 0
        while (leadingWindows < windowCount && windowRms[leadingWindows] < RMS_SILENCE_THRESHOLD) {
            leadingWindows++
        }

        var trailingWindows = 0
        while (trailingWindows < windowCount && windowRms[windowCount - 1 - trailingWindows] < RMS_SILENCE_THRESHOLD) {
            trailingWindows++
        }

        val targetLeadSamples = ((maxLeadingSilencePaddingMs * sampleRate) / 1000L).toInt()
        val targetTrailSamples = ((maxTrailingSilencePaddingMs * sampleRate) / 1000L).toInt()

        val actualLeadSamples = leadingWindows * WINDOW_SIZE
        val actualTrailSamples = trailingWindows * WINDOW_SIZE

        val trimLeadSamples = maxOf(0, actualLeadSamples - targetLeadSamples)
        val trimTrailSamples = maxOf(0, actualTrailSamples - targetTrailSamples)

        val startIndex = trimLeadSamples
        val endIndex = pcm.size - trimTrailSamples

        if (startIndex >= endIndex) return pcm

        val trimmed = pcm.copyOfRange(startIndex, endIndex)

        // Apply short 5ms linear fade-in and fade-out to prevent clicks
        val fadeSamples = minOf(((fadeDurationMs * sampleRate) / 1000L).toInt(), trimmed.size / 2)
        if (fadeSamples > 0) {
            for (i in 0 until fadeSamples) {
                val factor = i.toFloat() / fadeSamples.toFloat()
                trimmed[i] = (trimmed[i] * factor).toInt().toShort()
                val tailIdx = trimmed.size - 1 - i
                trimmed[tailIdx] = (trimmed[tailIdx] * factor).toInt().toShort()
            }
        }

        return trimmed
    }
}
