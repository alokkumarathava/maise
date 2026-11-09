package com.danemadsen.maise.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.danemadsen.maise.copyAssetToFile
import com.danemadsen.maise.getAssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

private const val TAG = "KokoroTTS"
private const val STYLE_DIM = 256
private const val MAX_PHONEME_LENGTH = 510
const val SAMPLE_RATE = 24000

/**
 * Splits [text] into natural, prosodically coherent sentence chunks for TTS synthesis.
 * Delegates to [AdaptiveChunker.chunkText].
 */
fun splitSentences(text: String): List<String> = AdaptiveChunker.chunkText(text)

@Deprecated("Fibonacci chunking created unnatural speech pauses. Use splitSentences instead.", ReplaceWith("splitSentences(text)"))
fun splitIntoFibonacciChunks(text: String): List<String> = splitSentences(text)

/**
 * Full Kokoro TTS pipeline.
 *
 * Usage:
 *   val tts = KokoroTTS(context)
 *   val pcm = tts.synthesize("Hello world", "en-US-heart")
 *   tts.close()
 */
class KokoroTTS(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val phonemizer: OpenPhonemizer
    private val session: OrtSession
    private val voiceStyleCache = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()

    init {
        phonemizer = OpenPhonemizer(context, env)

        val modelFile = copyAssetToFile(context, "kokoro-quantized.onnx")
        val availableCores = Runtime.getRuntime().availableProcessors()
        val threads = minOf(maxOf(availableCores / 2, 2), 4)

        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setIntraOpNumThreads(threads)
            runCatching { addXnnpack(emptyMap()) }
        }
        session = env.createSession(modelFile.absolutePath, opts)

        // Warm up ONNX session tensors off main thread if needed
        runCatching {
            warmup()
        }
    }

    private fun warmup() {
        val startMs = SystemClock.elapsedRealtime()
        var inputIds: OnnxTensor? = null
        var style: OnnxTensor? = null
        var speedTensor: OnnxTensor? = null
        try {
            val dummyText = "."
            val phonemes = phonemizer.phonemize(dummyText)
            val tokens = OpenPhonemizerOutputTokenizer.encode(phonemes)
            val nTokens = minOf(maxOf(tokens.size - 2, 0), MAX_PHONEME_LENGTH - 1)
            val styleBuf = loadVoiceStyleBuffer(DEFAULT_VOICE_ID, nTokens)

            val inputIdsBuf = LongBuffer.wrap(LongArray(tokens.size) { tokens[it].toLong() })
            inputIds = OnnxTensor.createTensor(env, inputIdsBuf, longArrayOf(1, tokens.size.toLong()))

            style = OnnxTensor.createTensor(env, styleBuf, longArrayOf(1, STYLE_DIM.toLong()))

            val speedBuf = FloatBuffer.wrap(floatArrayOf(1.0f))
            speedTensor = OnnxTensor.createTensor(env, speedBuf, longArrayOf(1))

            val inputs = mapOf(
                "input_ids" to inputIds,
                "style"     to style,
                "speed"     to speedTensor,
            )

            session.run(inputs).use { _ -> }

            Log.d(TAG, "KokoroTTS session warmup complete in ${SystemClock.elapsedRealtime() - startMs}ms")
        } catch (e: Exception) {
            Log.w(TAG, "KokoroTTS warmup encountered non-fatal exception", e)
        } finally {
            inputIds?.close()
            style?.close()
            speedTensor?.close()
        }
    }

    /**
     * Synthesize [text] using [voiceId] at [speed] (1.0 = normal).
     * Returns raw 16-bit PCM samples at [SAMPLE_RATE] Hz, mono.
     */
    fun synthesize(text: String, voiceId: String, speed: Float = 1.0f): ShortArray {
        val synthStartMs = SystemClock.elapsedRealtime()
        val tid = Process.myTid()
        val prevPriority = runCatching { Process.getThreadPriority(tid) }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)

        // Do NOT use THREAD_PRIORITY_BACKGROUND — run ONNX inference on performance cores!
        runCatching { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_AUDIO) }

        var inputIds: OnnxTensor? = null
        var style: OnnxTensor? = null
        var speedTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null

        try {
            // 1. Phonemize
            val phonemes = phonemizer.phonemize(text)

            // 2. Tokenize phonemes (wraps with special token 0 on both ends)
            val tokens = OpenPhonemizerOutputTokenizer.encode(phonemes)

            // n_tokens is phoneme count without the two wrapping special tokens, capped at 509
            val nTokens = minOf(maxOf(tokens.size - 2, 0), MAX_PHONEME_LENGTH - 1)

            // 3. Load voice style embedding buffer (zero-copy sub-view)
            val styleBuf = loadVoiceStyleBuffer(voiceId, nTokens)

            // 4. Build ONNX inputs
            val inputIdsBuf = LongBuffer.wrap(LongArray(tokens.size) { tokens[it].toLong() })
            inputIds = OnnxTensor.createTensor(env, inputIdsBuf, longArrayOf(1, tokens.size.toLong()))

            style = OnnxTensor.createTensor(env, styleBuf, longArrayOf(1, STYLE_DIM.toLong()))

            val speedBuf = FloatBuffer.wrap(floatArrayOf(speed))
            speedTensor = OnnxTensor.createTensor(env, speedBuf, longArrayOf(1))

            // 5. Run inference
            val inputs = mapOf(
                "input_ids" to inputIds,
                "style"     to style,
                "speed"     to speedTensor,
            )
            results = session.run(inputs)

            // 6. Extract waveform — shape [1, N] or [N]
            val waveform: FloatArray = extractWaveform(results[0].value)

            // 7. Convert float32 [-1, 1] → int16 PCM
            val pcm = ShortArray(waveform.size) { i ->
                (waveform[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }

            val elapsedMs = SystemClock.elapsedRealtime() - synthStartMs
            PhonemizerMetrics.totalSynthesisTimeMs.addAndGet(elapsedMs)
            PhonemizerMetrics.totalAudioSamples.addAndGet(pcm.size.toLong())

            return pcm
        } finally {
            inputIds?.close()
            style?.close()
            speedTensor?.close()
            results?.close()
            runCatching { Process.setThreadPriority(tid, prevPriority) }
        }
    }

    private fun loadVoiceStyleBuffer(voiceId: String, nTokens: Int): FloatBuffer {
        val resolvedVoiceId = if (findVoiceById(voiceId) != null) voiceId else DEFAULT_VOICE_ID

        val allFloats = voiceStyleCache.getOrPut(resolvedVoiceId) {
            try {
                val assetPath = "voices/$resolvedVoiceId.bin"
                val bytes = getAssetManager(context).open(assetPath).use { it.readBytes() }
                val floatCount = bytes.size / 4
                val floats = FloatArray(floatCount)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                floats
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load voice style asset for $resolvedVoiceId; falling back to zero style", e)
                FloatArray((MAX_PHONEME_LENGTH + 1) * STYLE_DIM)
            }
        }

        val floatCount = allFloats.size
        val rawOffset = nTokens * STYLE_DIM
        val offset = if (rawOffset + STYLE_DIM <= floatCount) rawOffset else maxOf(0, floatCount - STYLE_DIM)
        return FloatBuffer.wrap(allFloats, offset, STYLE_DIM)
    }

    fun loadVoiceStyle(voiceId: String, nTokens: Int): FloatArray {
        val buf = loadVoiceStyleBuffer(voiceId, nTokens)
        val dst = FloatArray(STYLE_DIM)
        buf.get(dst)
        return dst
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractWaveform(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*>  -> {
            // Shape [1, N] → flatten
            val inner = (value as Array<FloatArray>)[0]
            inner
        }
        else -> FloatArray(0)
    }

    fun close() {
        phonemizer.close()
        session.close()
        // OrtEnvironment is a singleton — do not close it
    }
}
