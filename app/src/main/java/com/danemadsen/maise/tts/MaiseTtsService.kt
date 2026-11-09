package com.danemadsen.maise.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.util.Locale

private const val TAG = "MaiseTtsService"
private const val PREFS_NAME = "maise_tts_prefs"
private const val PREF_VOICE = "selected_voice"
private const val INIT_TIMEOUT_MS = 10000L

class MaiseTtsService : TextToSpeechService() {

    @Volatile
    private var isStopped = false

    @Volatile
    private var activeOrchestrator: SynthesisOrchestrator? = null

    override fun onCreate() {
        super.onCreate()
        Thread {
            runBlocking {
                TtsEngineManager.initialize(applicationContext)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeOrchestrator?.cancel()
        activeOrchestrator = null
    }

    // -------------------------------------------------------------------------
    // Language support — availability is checked against the shipped voices.
    // -------------------------------------------------------------------------

    @Volatile
    private var loadedLocale: Locale? = null

    internal fun matchesLanguage(locale: Locale, lang: String?): Boolean {
        if (lang.isNullOrBlank()) return false
        val cleanLang = lang.trim().takeWhile { it != '-' && it != '_' }
        if (locale.language.equals(cleanLang, ignoreCase = true)) return true
        val iso3 = runCatching { locale.isO3Language }.getOrNull()
        if (iso3 != null && iso3.equals(cleanLang, ignoreCase = true)) return true
        return false
    }

    internal fun matchesCountry(locale: Locale, country: String?): Boolean {
        if (country.isNullOrBlank()) return false
        val cleanCountry = country.trim()
        if (locale.country.equals(cleanCountry, ignoreCase = true)) return true
        val iso3 = runCatching { locale.isO3Country }.getOrNull()
        if (iso3 != null && iso3.equals(cleanCountry, ignoreCase = true)) return true
        return false
    }

    internal fun availabilityOf(lang: String?, country: String?): Int {
        if (lang.isNullOrBlank()) return TextToSpeech.LANG_NOT_SUPPORTED
        val hasLanguage = ALL_VOICES.any { matchesLanguage(it.locale, lang) }
        if (!hasLanguage) return TextToSpeech.LANG_NOT_SUPPORTED
        val hasCountry = !country.isNullOrBlank() &&
            ALL_VOICES.any { matchesLanguage(it.locale, lang) && matchesCountry(it.locale, country) }
        return if (hasCountry) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        availabilityOf(lang, country)

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = availabilityOf(lang, country)
        if (availability >= TextToSpeech.LANG_AVAILABLE) {
            val matchedVoiceLocale = ALL_VOICES.firstOrNull { voice ->
                matchesLanguage(voice.locale, lang) && (!country.isNullOrBlank() && matchesCountry(voice.locale, country))
            }?.locale ?: ALL_VOICES.firstOrNull { matchesLanguage(it.locale, lang) }?.locale

            loadedLocale = matchedVoiceLocale ?: runCatching {
                Locale.Builder().apply {
                    if (!lang.isNullOrBlank()) setLanguage(lang.trim().takeWhile { it != '-' && it != '_' })
                    if (!country.isNullOrBlank()) setRegion(country.trim())
                }.build()
            }.getOrNull() ?: loadedLocale
        }
        return availability
    }

    override fun onGetLanguage(): Array<String> {
        val locale = loadedLocale ?: Locale.US
        val iso3Lang = runCatching { locale.isO3Language }.getOrNull() ?: locale.language
        val iso3Country = runCatching { locale.isO3Country }.getOrNull() ?: locale.country
        return arrayOf(iso3Lang, iso3Country, "")
    }

    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String): String =
        voiceMatching(lang, country) ?: DEFAULT_VOICE_ID

    internal fun voiceMatching(lang: String?, country: String?): String? {
        if (lang.isNullOrBlank()) return null
        val sameLanguage = ALL_VOICES.filter { matchesLanguage(it.locale, lang) }
        return sameLanguage.firstOrNull { matchesCountry(it.locale, country) }?.id
            ?: sameLanguage.firstOrNull()?.id
    }

    override fun onGetFeaturesForLanguage(lang: String, country: String, variant: String): Set<String> = emptySet()

    // -------------------------------------------------------------------------
    // Voice enumeration (Android 5.0+ API)
    // -------------------------------------------------------------------------

    override fun onGetVoices(): MutableList<Voice> {
        return ALL_VOICES.map { info ->
            Voice(
                info.id,
                info.locale,
                Voice.QUALITY_HIGH,
                Voice.LATENCY_HIGH,
                false,
                emptySet()
            )
        }.toMutableList()
    }

    override fun onIsValidVoiceName(voiceName: String): Int =
        if (findVoiceById(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String): Int =
        if (findVoiceById(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    // -------------------------------------------------------------------------
    // Synthesis
    // -------------------------------------------------------------------------

    override fun onStop() {
        Log.d(TAG, "onStop() — cancelling synthesis")
        isStopped = true
        activeOrchestrator?.cancel()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        isStopped = false

        val requestedVoice = request.voiceName
            ?.takeIf { it.isNotEmpty() }
            ?: voiceMatching(request.language, request.country)
            ?: getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_VOICE, DEFAULT_VOICE_ID)
        val voiceId = requestedVoice?.takeIf { findVoiceById(it) != null } ?: DEFAULT_VOICE_ID

        val text = request.charSequenceText?.toString()?.takeIf { it.isNotBlank() }
            ?: run { callback.done(); return }

        val speed = (request.speechRate / 100f).coerceIn(0.5f, 2.0f)
        Log.d(TAG, "onSynthesizeText: voice=$voiceId, rate=$speed, textLen=${text.length}")
        val requestStartTimeMs = android.os.SystemClock.elapsedRealtime()

        callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)

        val engine = TtsEngineManager.getEngineSync(applicationContext, INIT_TIMEOUT_MS) ?: run {
            Log.e(TAG, "TTS engine not ready after ${INIT_TIMEOUT_MS}ms — returning error")
            callback.error()
            return
        }

        if (isStopped) {
            Log.d(TAG, "Stopped before synthesis — exiting early")
            callback.done()
            return
        }

        val orchestrator = SynthesisOrchestrator(engine)
        activeOrchestrator = orchestrator

        try {
            var firstChunkEver = true
            var chunkCallbackCount = 0
            runBlocking {
                orchestrator.synthesizeStream(text, voiceId, speed) { pcm ->
                    if (isStopped || orchestrator.isCancelled) return@synthesizeStream

                    if (firstChunkEver) {
                        val ttfaMs = android.os.SystemClock.elapsedRealtime() - requestStartTimeMs
                        PhonemizerMetrics.lastTtfaMs.set(ttfaMs)
                        Log.d(TAG, "First audioAvailable() TTFA: ${ttfaMs}ms")
                        firstChunkEver = false
                    }

                    val maxBytes = if (callback.maxBufferSize > 0) callback.maxBufferSize else 8192
                    val chunkSamples = maxOf(256, minOf(4096, maxBytes / 2))
                    var offset = 0
                    while (offset < pcm.size && !isStopped && !orchestrator.isCancelled) {
                        val end = minOf(offset + chunkSamples, pcm.size)
                        val byteCount = (end - offset) * 2
                        val buf = ByteArray(byteCount)
                        for (i in offset until end) {
                            val s = pcm[i]
                            val byteIdx = (i - offset) * 2
                            buf[byteIdx]     = (s.toInt() and 0xFF).toByte()
                            buf[byteIdx + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
                        }
                        callback.audioAvailable(buf, 0, byteCount)
                        chunkCallbackCount++
                        offset = end
                    }
                }
            }

            if (isStopped || orchestrator.isCancelled) {
                Log.d(TAG, "Synthesis stopped/cancelled during processing")
            } else {
                Log.d(TAG, "Synthesis complete: delivered $chunkCallbackCount buffers to System TTS callback, metrics: ${PhonemizerMetrics.getReport()}")
            }
            callback.done()
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed", e)
            callback.error()
        } finally {
            activeOrchestrator = null
        }
    }

}
