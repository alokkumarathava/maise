package com.danemadsen.maise.tts

import android.content.Context
import com.danemadsen.maise.getAssetManager
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

sealed class LookupResult {
    data class OverrideHit(val phonemes: String) : LookupResult()
    data class BaseHit(val phonemes: String) : LookupResult()
    data class CacheHit(val phonemes: String) : LookupResult()
    object Miss : LookupResult()
}

/**
 * Shared, process-wide repository for pronunciation dictionaries and LRU fallback cache.
 * Loads base dictionary and overrides once per process lifetime.
 */
object PronunciationRepository {

    @Volatile
    private var isLoaded = false
    private val lock = Any()

    private var baseDictionary: Map<String, String> = emptyMap()
    private var overrideDictionary: Map<String, String> = emptyMap()

    private const val MAX_FALLBACK_CACHE_SIZE = 2048
    private val fallbackCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > MAX_FALLBACK_CACHE_SIZE
            }
        }
    )

    fun ensureLoaded(context: Context) {
        if (isLoaded) return
        synchronized(lock) {
            if (isLoaded) return
            val startTime = System.currentTimeMillis()

            val baseMap = loadJsonDictionary(
                streamProvider = { getAssetManager(context).open("dictionary.json") }
            )
            val overrideMap = loadJsonDictionary(
                streamProvider = { getAssetManager(context).open("pronunciation_overrides.json") }
            )

            baseDictionary = baseMap
            overrideDictionary = overrideMap
            isLoaded = true

            val elapsedTime = System.currentTimeMillis() - startTime
            PhonemizerMetrics.dictionaryLoadCount.incrementAndGet()
            PhonemizerMetrics.dictionaryInitTimeMs.set(elapsedTime)
        }
    }

    /**
     * Direct loading helper used for testing or custom streams without requiring Android Context.
     */
    fun loadFromStreams(baseStream: InputStream?, overrideStream: InputStream?) {
        synchronized(lock) {
            val baseMap = baseStream?.use { parseDictionaryStream(it) } ?: emptyMap()
            val overrideMap = overrideStream?.use { parseDictionaryStream(it) } ?: emptyMap()
            baseDictionary = baseMap
            overrideDictionary = overrideMap
            isLoaded = true
        }
    }

    fun clearForTesting() {
        synchronized(lock) {
            baseDictionary = emptyMap()
            overrideDictionary = emptyMap()
            fallbackCache.clear()
            isLoaded = false
        }
    }

    private fun loadJsonDictionary(streamProvider: () -> InputStream): Map<String, String> {
        return try {
            streamProvider().use { stream ->
                parseDictionaryStream(stream)
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseDictionaryStream(stream: InputStream): Map<String, String> {
        val map = HashMap<String, String>(128_000)
        val reader = InputStreamReader(stream, Charsets.UTF_8).buffered()

        var inEnUs = false
        var line: String? = reader.readLine()
        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.contains("\"en_us\"")) {
                inEnUs = true
            } else if (inEnUs) {
                if (trimmed.startsWith("}") || trimmed == "}") {
                    break
                }
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx > 0) {
                    val rawKey = trimmed.substring(0, colonIdx).trim()
                    var rawVal = trimmed.substring(colonIdx + 1).trim()
                    if (rawVal.endsWith(",")) {
                        rawVal = rawVal.substring(0, rawVal.length - 1).trim()
                    }
                    val key = rawKey.removeSurrounding("\"")
                    val valStr = rawVal.removeSurrounding("\"")
                    val word = key.lowercase(Locale.ROOT).trim()
                    val phonemes = valStr.trim()
                    if (word.isNotEmpty() && phonemes.isNotEmpty()) {
                        map[word] = phonemes
                    }
                }
            }
            line = reader.readLine()
        }
        return map
    }

    fun lookup(rawWord: String): LookupResult {
        val word = rawWord.lowercase(Locale.ROOT).trim()
        if (word.isEmpty()) return LookupResult.Miss

        overrideDictionary[word]?.let { phonemes ->
            PhonemizerMetrics.overrideHitCount.incrementAndGet()
            PhonemizerMetrics.dictionaryHitCount.incrementAndGet()
            return LookupResult.OverrideHit(phonemes)
        }

        baseDictionary[word]?.let { phonemes ->
            PhonemizerMetrics.dictionaryHitCount.incrementAndGet()
            return LookupResult.BaseHit(phonemes)
        }

        fallbackCache[word]?.let { phonemes ->
            PhonemizerMetrics.fallbackCacheHitCount.incrementAndGet()
            return LookupResult.CacheHit(phonemes)
        }

        PhonemizerMetrics.dictionaryMissCount.incrementAndGet()
        return LookupResult.Miss
    }

    fun cacheFallback(rawWord: String, phonemes: String) {
        val word = rawWord.lowercase(Locale.ROOT).trim()
        val cleanedPhonemes = phonemes.trim()
        if (word.isNotEmpty() && isValidPhonemeSequence(cleanedPhonemes)) {
            fallbackCache[word] = cleanedPhonemes
        }
    }

    fun isValidPhonemeSequence(phonemes: String): Boolean {
        if (phonemes.isBlank()) return false
        for (ch in phonemes) {
            val tokenEncoded = OpenPhonemizerOutputTokenizer.encode(ch.toString())
            if (tokenEncoded.size == 3 && tokenEncoded[1] == 0) {
                return false
            }
        }
        return true
    }

    fun isLoaded(): Boolean = isLoaded
}
