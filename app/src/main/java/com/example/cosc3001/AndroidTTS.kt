package com.example.cosc3001

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight wrapper around Android's native TextToSpeech engine.
 * Added: speech state listeners + isSpeaking flag so UI can disable interactions during playback.
 */
class AndroidTTS(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    @Volatile private var initialized = false
    @Volatile var isSpeaking: Boolean = false
        private set
    private var pendingUtterance: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateListeners = mutableListOf<(Boolean) -> Unit>()
    private val onDoneCallbacks = ConcurrentHashMap<String, () -> Unit>()

    // Configuration
    var language: Locale = Locale.US
    var speechRate: Float = 1.0f
    var pitch: Float = 1.0f

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(language)
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(pitch)
            attachProgressListener()
            initialized = true
            Log.d(TAG, "Android TTS initialized lang=$language rate=$speechRate pitch=$pitch result=$result")
            pendingUtterance?.let {
                Log.d(TAG, "Speaking pending utterance queued before init")
                speak(it)
                pendingUtterance = null
            }
        } else {
            Log.e(TAG, "Android TTS initialization failed: status=$status")
        }
    }

    private fun attachProgressListener() {
        try {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    updateSpeaking(true)
                }
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { id -> onDoneCallbacks.remove(id)?.let { cb -> runCatching { cb() } } }
                    updateSpeaking(false)
                }
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { id -> onDoneCallbacks.remove(id)?.let { cb -> runCatching { cb() } } }
                    updateSpeaking(false)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    utteranceId?.let { id -> onDoneCallbacks.remove(id)?.let { cb -> runCatching { cb() } } }
                    updateSpeaking(false)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach UtteranceProgressListener: ${e.message}")
        }
    }

    private fun updateSpeaking(speaking: Boolean) {
        isSpeaking = speaking
        mainHandler.post {
            stateListeners.forEach { listener ->
                runCatching { listener(speaking) }
            }
        }
    }

    fun addSpeechStateListener(listener: (Boolean) -> Unit) {
        stateListeners.add(listener)
    }

    /** Speak the provided text. If engine not ready yet, text is queued (last one wins). */
    fun speak(text: String?, flush: Boolean = true, onDone: (() -> Unit)? = null) {
        if (text.isNullOrBlank()) return
        if (!initialized) {
            pendingUtterance = text
            // Can't persist callback reliably before init; fire synchronously if provided (rare case)
            onDone?.invoke()
            return
        }
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = "tts_${System.currentTimeMillis()}_${text.hashCode()}"
        if (onDone != null) onDoneCallbacks[utteranceId] = onDone
        val res = tts?.speak(text, queueMode, null, utteranceId)
        Log.d(TAG, "Requested speak len=${text.length} id=$utteranceId result=$res flush=$flush")
        // Assume speaking started
        updateSpeaking(true)
    }

    /** Stop any current speech. */
    fun stop() {
        try { tts?.stop(); updateSpeaking(false) } catch (_: Exception) {}
    }

    /** Shutdown and release the engine. */
    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        initialized = false
        pendingUtterance = null
        updateSpeaking(false)
        stateListeners.clear()
    }

    companion object { private const val TAG = "AndroidTTS" }
}
