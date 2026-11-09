package com.danemadsen.maise.tts

import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic metrics and counters for the TTS dictionary lookup and phonemization pipeline.
 */
object PhonemizerMetrics {
    val dictionaryHitCount = AtomicLong(0)
    val overrideHitCount = AtomicLong(0)
    val dictionaryMissCount = AtomicLong(0)
    val fallbackCacheHitCount = AtomicLong(0)
    val phonemizerOnnxCallCount = AtomicLong(0)
    val dictionaryLoadCount = AtomicLong(0)
    val dictionaryInitTimeMs = AtomicLong(0)
    val totalPhonemizationTimeNs = AtomicLong(0)
    val lastTtfaMs = AtomicLong(0)
    val totalSynthesisTimeMs = AtomicLong(0)
    val totalAudioSamples = AtomicLong(0)
    val totalChunksCount = AtomicLong(0)
    val totalLeadingSilenceMs = AtomicLong(0)
    val totalTrailingSilenceMs = AtomicLong(0)
    val totalConsumerWaitMs = AtomicLong(0)
    val audioTrackUnderrunCount = AtomicLong(0)

    fun reset() {
        dictionaryHitCount.set(0)
        overrideHitCount.set(0)
        dictionaryMissCount.set(0)
        fallbackCacheHitCount.set(0)
        phonemizerOnnxCallCount.set(0)
        dictionaryLoadCount.set(0)
        dictionaryInitTimeMs.set(0)
        totalPhonemizationTimeNs.set(0)
        lastTtfaMs.set(0)
        totalSynthesisTimeMs.set(0)
        totalAudioSamples.set(0)
        totalChunksCount.set(0)
        totalLeadingSilenceMs.set(0)
        totalTrailingSilenceMs.set(0)
        totalConsumerWaitMs.set(0)
        audioTrackUnderrunCount.set(0)
    }

    val currentRtf: Float
        get() {
            val audioMs = (totalAudioSamples.get() * 1000L) / 24000L
            val synthMs = totalSynthesisTimeMs.get()
            return if (audioMs > 0) synthMs.toFloat() / audioMs.toFloat() else 0f
        }

    fun getReport(): Map<String, Long> {
        return mapOf(
            "dictionary_hit_count" to dictionaryHitCount.get(),
            "override_hit_count" to overrideHitCount.get(),
            "dictionary_miss_count" to dictionaryMissCount.get(),
            "fallback_cache_hit_count" to fallbackCacheHitCount.get(),
            "phonemizer_onnx_call_count" to phonemizerOnnxCallCount.get(),
            "dictionary_load_count" to dictionaryLoadCount.get(),
            "dictionary_init_time_ms" to dictionaryInitTimeMs.get(),
            "total_phonemization_time_ns" to totalPhonemizationTimeNs.get(),
            "last_ttfa_ms" to lastTtfaMs.get(),
            "total_synthesis_time_ms" to totalSynthesisTimeMs.get(),
            "total_audio_samples" to totalAudioSamples.get(),
            "total_chunks_count" to totalChunksCount.get(),
            "total_leading_silence_ms" to totalLeadingSilenceMs.get(),
            "total_trailing_silence_ms" to totalTrailingSilenceMs.get(),
            "total_consumer_wait_ms" to totalConsumerWaitMs.get(),
            "audiotrack_underrun_count" to audioTrackUnderrunCount.get(),
            "rtf_x1000" to (currentRtf * 1000f).toLong()
        )
    }
}
