package com.danemadsen.maise.tts

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SynthesisOrchestrator"
private const val QUEUE_CAPACITY = 3

/**
 * Reusable producer-consumer synthesis orchestrator.
 * Coordinates speech chunk synthesis, bounded queue pre-fetching, single-synthesis locking,
 * and immediate cancellation for both System TTS and UI playback.
 */
class SynthesisOrchestrator(
    private val ttsEngine: KokoroTTS
) {

    private val synthesisMutex = Mutex()
    private val isCancelledFlag = AtomicBoolean(false)

    val isCancelled: Boolean get() = isCancelledFlag.get()

    /**
     * Synthesizes [text] asynchronously using [AdaptiveChunker] chunks and delivers each chunk
     * PCM buffer to [onChunkReady].
     *
     * @param voiceId The requested voice ID.
     * @param speed Speech rate scalar (1.0 = normal).
     * @param onChunkReady Callback invoked sequentially on consumer coroutine with each synthesized [ShortArray] chunk.
     */
    suspend fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float = 1.0f,
        onChunkReady: suspend (pcm: ShortArray) -> Unit
    ): Boolean = withContext(Dispatchers.Default) {
        isCancelledFlag.set(false)
        val chunks = AdaptiveChunker.chunkText(text)
        if (chunks.isEmpty() || isCancelled) return@withContext true

        Log.d(TAG, "Starting synthesis stream for ${chunks.size} chunks (voice=$voiceId, rate=$speed)")

        synthesisMutex.withLock {
            if (isCancelled) return@withContext true

            val channel = Channel<ShortArray>(capacity = QUEUE_CAPACITY)

            coroutineScope {
                // Producer job: synthesize chunks and send to bounded channel
                val producerJob = launch(Dispatchers.Default) {
                    try {
                        for ((index, chunk) in chunks.withIndex()) {
                            if (isCancelledFlag.get()) break
                            val chunkStartMs = SystemClock.elapsedRealtime()
                            val rawPcm = ttsEngine.synthesize(chunk, voiceId, speed)
                            val synthDurationMs = SystemClock.elapsedRealtime() - chunkStartMs

                            val metrics = PcmSilenceProcessor.analyzeChunk(rawPcm, synthDurationMs)
                            PhonemizerMetrics.totalChunksCount.incrementAndGet()
                            PhonemizerMetrics.totalLeadingSilenceMs.addAndGet(metrics.leadingSilenceMs)
                            PhonemizerMetrics.totalTrailingSilenceMs.addAndGet(metrics.trailingSilenceMs)

                            Log.d(TAG, "Synthesized chunk $index/${chunks.size} (${rawPcm.size} samples, duration=${metrics.audioDurationMs}ms, leadingSilence=${metrics.leadingSilenceMs}ms, trailingSilence=${metrics.trailingSilenceMs}ms) in ${synthDurationMs}ms (RTF: ${"%.2f".format(metrics.rtf)})")

                            if (isCancelledFlag.get()) break
                            val finalPcm = PcmSilenceProcessor.processChunk(rawPcm)
                            channel.send(finalPcm)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during synthesis producer step", e)
                    } finally {
                        channel.close()
                    }
                }

                // Consumer step: receive and process each PCM chunk
                try {
                    var lastReceiveTimeMs = SystemClock.elapsedRealtime()
                    for (pcm in channel) {
                        if (isCancelledFlag.get()) break
                        val consumerWaitMs = SystemClock.elapsedRealtime() - lastReceiveTimeMs
                        if (consumerWaitMs > 50) {
                            PhonemizerMetrics.totalConsumerWaitMs.addAndGet(consumerWaitMs)
                            Log.d(TAG, "Consumer waited ${consumerWaitMs}ms for next chunk")
                        }
                        onChunkReady(pcm)
                        lastReceiveTimeMs = SystemClock.elapsedRealtime()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during synthesis consumer step", e)
                } finally {
                    producerJob.cancel()
                }
            }
        }

        return@withContext !isCancelledFlag.get()
    }

    /**
     * Cancels the current synthesis stream immediately.
     */
    fun cancel() {
        Log.d(TAG, "SynthesisOrchestrator.cancel() called")
        isCancelledFlag.set(true)
    }
}
