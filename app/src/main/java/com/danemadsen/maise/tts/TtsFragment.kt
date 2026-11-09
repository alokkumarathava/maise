package com.danemadsen.maise.tts

import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.danemadsen.maise.databinding.FragmentTtsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "maise_tts_prefs"
private const val PREF_VOICE = "selected_voice"

class TtsFragment : Fragment() {

    private var _binding: FragmentTtsBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: SharedPreferences
    private var audioTrack: AudioTrack? = null
    private var activeOrchestrator: SynthesisOrchestrator? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTtsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

        val voiceLabels = ALL_VOICES.map { it.toString() }
        binding.voiceSpinner.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, voiceLabels)

        val savedVoiceId = prefs.getString(PREF_VOICE, DEFAULT_VOICE_ID) ?: DEFAULT_VOICE_ID
        val savedIndex = ALL_VOICES.indexOfFirst { it.id == savedVoiceId }.takeIf { it >= 0 } ?: 0
        binding.voiceSpinner.setSelection(savedIndex)

        setStatus("Loading models\u2026")
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val success = TtsEngineManager.initialize(requireContext())
            withContext(Dispatchers.Main) {
                if (success) setStatus("Ready") else setStatus("Error loading models")
            }
        }

        binding.speakButton.setOnClickListener { onSpeakClicked() }
        binding.openSettingsButton.setOnClickListener {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        }
        binding.reportButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Mobile-Artificial-Intelligence/maise/issues")))
        }
    }

    private fun onSpeakClicked() {
        val text = binding.inputText.text?.toString()?.trim()
        if (text.isNullOrEmpty()) { setStatus("Please enter some text."); return }

        val engine = TtsEngineManager.getEngineIfReady() ?: run { setStatus("Engine not ready yet."); return }
        val voiceInfo = ALL_VOICES[binding.voiceSpinner.selectedItemPosition]
        prefs.edit().putString(PREF_VOICE, voiceInfo.id).apply()

        binding.speakButton.isEnabled = false
        setStatus("Synthesizing\u2026")

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val startTimeMs = android.os.SystemClock.elapsedRealtime()
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )

                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack?.play()

                val orchestrator = SynthesisOrchestrator(engine)
                activeOrchestrator = orchestrator

                var ttfaMs = 0L
                var first = true
                var totalWrittenSamples = 0L

                val initialUnderruns = audioTrack?.underrunCount ?: 0

                orchestrator.synthesizeStream(text, voiceInfo.id) { pcm ->
                    if (first) {
                        ttfaMs = android.os.SystemClock.elapsedRealtime() - startTimeMs
                        PhonemizerMetrics.lastTtfaMs.set(ttfaMs)
                        withContext(Dispatchers.Main) { setStatus("Playing\u2026 (TTFA: ${ttfaMs}ms)") }
                        first = false
                    }

                    var written = 0
                    while (written < pcm.size && !orchestrator.isCancelled) {
                        val track = audioTrack ?: break
                        val count = track.write(pcm, written, pcm.size - written)
                        if (count <= 0) break
                        written += count
                    }
                    totalWrittenSamples += written
                }

                // Drain audio track before stopping so final audio is not clipped
                while (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING && !orchestrator.isCancelled) {
                    val headPos = audioTrack?.playbackHeadPosition?.toLong() ?: 0L
                    if (headPos >= totalWrittenSamples) break
                    delay(20)
                }

                val finalUnderruns = audioTrack?.underrunCount ?: 0
                val sessionUnderruns = maxOf(0, finalUnderruns - initialUnderruns)
                if (sessionUnderruns > 0) {
                    PhonemizerMetrics.audioTrackUnderrunCount.addAndGet(sessionUnderruns.toLong())
                    android.util.Log.w("TtsFragment", "AudioTrack experienced $sessionUnderruns underruns during playback")
                }

                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null

                val rtf = PhonemizerMetrics.currentRtf
                val statusMsg = "Done (TTFA: ${ttfaMs}ms, RTF: ${"%.2f".format(rtf)}x)"
                android.util.Log.d("TtsFragment", "Playback metrics: ${PhonemizerMetrics.getReport()}")
                withContext(Dispatchers.Main) {
                    setStatus(statusMsg)
                    binding.speakButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("Error: ${e.message}")
                    binding.speakButton.isEnabled = true
                }
            } finally {
                activeOrchestrator = null
            }
        }
    }

    private fun setStatus(msg: String) { _binding?.statusText?.text = msg }

    override fun onDestroyView() {
        super.onDestroyView()
        activeOrchestrator?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        _binding = null
    }
}
