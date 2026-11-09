package com.danemadsen.maise.tts

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "TtsEngineManager"

/**
 * Lifecycle states of the process-wide TTS engine manager.
 */
enum class EngineState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    FAILED,
    CLOSED
}

/**
 * Thread-safe process-wide singleton lifecycle manager for [KokoroTTS].
 * Prevents duplicate ONNX session allocations and duplicate dictionary memory usage
 * across [MaiseTtsService] and [TtsFragment].
 */
@SuppressLint("StaticFieldLeak")
object TtsEngineManager {

    private val stateFlow = MutableStateFlow(EngineState.UNINITIALIZED)
    val state: StateFlow<EngineState> = stateFlow.asStateFlow()

    @Volatile
    private var ttsEngine: KokoroTTS? = null

    private val initLock = java.lang.Object()
    private val isInitializing = AtomicBoolean(false)

    /**
     * Initializes the shared [KokoroTTS] engine asynchronously off the main thread.
     * Safe to call repeatedly from any thread or component.
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (stateFlow.value == EngineState.READY && ttsEngine != null) {
            return@withContext true
        }

        if (isInitializing.compareAndSet(false, true)) {
            stateFlow.value = EngineState.INITIALIZING
            val startMs = SystemClock.elapsedRealtime()
            try {
                val appContext = context.applicationContext
                val engine = KokoroTTS(appContext)
                synchronized(initLock) {
                    ttsEngine = engine
                    stateFlow.value = EngineState.READY
                    initLock.notifyAll()
                }
                Log.i(TAG, "Shared KokoroTTS engine initialized successfully in ${SystemClock.elapsedRealtime() - startMs}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize shared KokoroTTS engine", e)
                synchronized(initLock) {
                    ttsEngine = null
                    stateFlow.value = EngineState.FAILED
                    initLock.notifyAll()
                }
            } finally {
                isInitializing.set(false)
            }
        }

        return@withContext awaitReady(timeoutMs = 15000L) != null
    }

    /**
     * Synchronously wait for the engine to be initialized with a timeout.
     * Useful for non-coroutine callers such as TextToSpeechService threads.
     */
    fun getEngineSync(context: Context, timeoutMs: Long = 10000L): KokoroTTS? {
        if (stateFlow.value == EngineState.READY) {
            return ttsEngine
        }

        // Trigger non-blocking initialization if uninitialized
        if (stateFlow.value == EngineState.UNINITIALIZED || stateFlow.value == EngineState.FAILED) {
            Thread {
                kotlinx.coroutines.runBlocking { initialize(context) }
            }.start()
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        synchronized(initLock) {
            while (stateFlow.value == EngineState.INITIALIZING) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                try {
                    initLock.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return if (stateFlow.value == EngineState.READY) ttsEngine else null
        }
    }

    /**
     * Suspends until the engine reaches [EngineState.READY] or until [timeoutMs] elapses.
     */
    suspend fun awaitReady(timeoutMs: Long = 10000L): KokoroTTS? {
        if (stateFlow.value == EngineState.READY) return ttsEngine

        val resultState = withTimeoutOrNull(timeoutMs) {
            stateFlow.first { it == EngineState.READY || it == EngineState.FAILED || it == EngineState.CLOSED }
        }

        return if (resultState == EngineState.READY) ttsEngine else null
    }

    /**
     * Gets the active engine instance if ready, or null.
     */
    fun getEngineIfReady(): KokoroTTS? {
        return if (stateFlow.value == EngineState.READY) ttsEngine else null
    }

    /**
     * Safely closes the shared engine.
     */
    fun close() {
        synchronized(initLock) {
            ttsEngine?.close()
            ttsEngine = null
            stateFlow.value = EngineState.CLOSED
            initLock.notifyAll()
        }
    }
}
