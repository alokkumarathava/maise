package com.danemadsen.maise.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import com.danemadsen.maise.copyAssetToFile
import java.nio.LongBuffer

private val PUNCTUATION_BEFORE = setOf(".", ",", "!", "?", ";", ":", ")", "]", "}", "\u00BB", "\u201D")
private val PUNCTUATION_AFTER  = setOf("(", "[", "{", "\u00AB", "\u201C")
private val WORD_REGEX = Regex("""[\w']+|[^\w\s]""")

// ID 0 = blank/pad ("_"), ID 1 = <en_us>, ID 2 = <end>, IDs 3-63 = phoneme symbols
private const val BLANK_ID = 0
private const val END_ID   = 2

class OpenPhonemizer(private val context: Context, private val env: OrtEnvironment) {

    private val session: OrtSession

    init {
        val modelFile = copyAssetToFile(context, "open-phonemizer.onnx")
        val availableCores = Runtime.getRuntime().availableProcessors()
        val threads = minOf(availableCores, 2)

        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setIntraOpNumThreads(threads)
            runCatching { addXnnpack(emptyMap()) }
        }
        session = env.createSession(modelFile.absolutePath, opts)
        PronunciationRepository.ensureLoaded(context)
    }

    /**
     * Convert a full sentence to IPA phonemes.
     * Words are looked up in the PronunciationRepository first; unknown words fall back to the model.
     */
    fun phonemize(text: String): String {
        val startTimeNs = SystemClock.elapsedRealtimeNanos()
        PronunciationRepository.ensureLoaded(context)

        val tokens = WORD_REGEX.findAll(normalizeText(text)).map { it.value }.toList()
        val sb = StringBuilder()

        for (token in tokens) {
            val isPunct = token.matches(Regex("""[^\w']+"""))

            if (isPunct) {
                when {
                    token in PUNCTUATION_BEFORE -> {
                        if (sb.endsWith(" ")) sb.deleteCharAt(sb.length - 1)
                        sb.append(token)
                    }
                    token in PUNCTUATION_AFTER -> sb.append(token)
                    else -> {
                        if (sb.isNotEmpty() && !sb.endsWith(" ")) sb.append(" ")
                        sb.append(token)
                    }
                }
            } else {
                if (sb.isNotEmpty() && !sb.endsWith(" ") &&
                    !sb.last().toString().let { it in PUNCTUATION_AFTER }
                ) {
                    sb.append(" ")
                }

                val phonemes = getOrGeneratePhonemes(token)
                sb.append(phonemes)
            }
        }

        val elapsedNs = SystemClock.elapsedRealtimeNanos() - startTimeNs
        PhonemizerMetrics.totalPhonemizationTimeNs.addAndGet(elapsedNs)

        return sb.toString().trim()
    }

    private fun getOrGeneratePhonemes(token: String): String {
        return when (val res = PronunciationRepository.lookup(token)) {
            is LookupResult.OverrideHit -> res.phonemes
            is LookupResult.BaseHit -> res.phonemes
            is LookupResult.CacheHit -> res.phonemes
            is LookupResult.Miss -> {
                val generated = phonemizeWord(token)
                PronunciationRepository.cacheFallback(token, generated)
                generated
            }
        }
    }

    private fun phonemizeWord(word: String): String {
        PhonemizerMetrics.phonemizerOnnxCallCount.incrementAndGet()
        val encoded = encode(word)
        val padded  = LongArray(64) { i -> if (i < encoded.size) encoded[i].toLong() else 0L }

        var inputTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null

        try {
            inputTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(padded),
                longArrayOf(1, 64)
            )

            results = session.run(mapOf("text" to inputTensor))
            // Output shape: [1, 64, 64]  (batch=1, seq_len=64, vocab=64)
            @Suppress("UNCHECKED_CAST")
            val logits = (results[0].value as Array<Array<FloatArray>>)[0]

            // 1. Argmax across vocab dimension for each position
            val argmax = IntArray(logits.size) { pos ->
                var best = 0
                for (i in 1 until logits[pos].size) {
                    if (logits[pos][i] > logits[pos][best]) best = i
                }
                best
            }

            // 2. CTC decode: collapse consecutive identical tokens, then remove blanks (ID 0)
            val sb = StringBuilder()
            var prev = -1
            for (id in argmax) {
                if (id == prev) continue          // remove consecutive duplicate
                prev = id
                if (id == BLANK_ID) continue      // remove blank/pad
                if (id == END_ID)   break         // stop at <end>
                val sym = tokenizer.phonemeSymbols[id] ?: continue
                if (sym.startsWith("<")) continue // skip any remaining special tokens
                sb.append(sym)
            }
            return sb.toString()
        } finally {
            inputTensor?.close()
            results?.close()
        }
    }

    fun close() {
        session.close()
    }
}
