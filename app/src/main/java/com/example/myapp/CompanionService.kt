package com.example.myapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CompanionService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.myapp.STOP"
        private const val CHANNEL = "companion"
        private const val NOTIF_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var worker: ExecutorService? = null
    private var active = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var focusReq: AudioFocusRequest? = null
    private var hasFocus = false

    private val history = JSONArray()
    private val replyBuf = StringBuilder()
    private val sentenceBuf = StringBuilder()
    private val boundary = Regex("(?<=[.!?])\\s+|\\n+")
    private var pending = 0
    private var streamDone = false
    private var turnOpen = false
    private var utteranceCounter = 0
    private var firstAudioLogged = false
    private var tEnd = 0L
    private var tResults = 0L
    private var tSentence = 0L
    private var awakeUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY

        prefs = Prefs(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        startInForeground()

        if (prefs.apiKey.isBlank()) { fail("Add your Gemini API key first"); return START_NOT_STICKY }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { fail("No speech recognition service on this phone"); return START_NOT_STICKY }

        active = true
        worker = Executors.newSingleThreadExecutor()
        CompanionState.update { UiState(running = true, status = "Starting...") }
        tts = TextToSpeech(this) { st -> main.post { onTtsInit(st) } }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Companion", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CompanionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Companion is on")
            .setContentText("Listening for your questions")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    // ---------- TTS ----------
    private fun onTtsInit(status: Int) {
        if (!active) return
        if (status != TextToSpeech.SUCCESS) { fail("Text-to-speech failed to start"); return }
        tts?.language = Locale.getDefault()
        tts?.setAudioAttributes(attrs)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { main.post { onSpeechStarted() } }
            override fun onDone(utteranceId: String?) { main.post { onUtteranceFinished() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { onUtteranceFinished() } }
        })
        startListening()
    }

    private fun speak(text: String) {
        val clean = text.replace(Regex("[*#`]"), "").trim()
        if (clean.isEmpty()) return
        if (tSentence == 0L) tSentence = SystemClock.elapsedRealtime()
        requestFocus()
        pending++
        tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, "u" + (utteranceCounter++))
    }

    private fun onSpeechStarted() {
        if (!active) return
        if (!firstAudioLogged) {
            firstAudioLogged = true
            val now = SystemClock.elapsedRealtime()
            val stt = if (tEnd > 0) tResults - tEnd else -1
            val llm = tSentence - tResults
            val ttsMs = now - tSentence
            CompanionState.update { it.copy(sttMs = stt, llmMs = llm, ttsMs = ttsMs) }
        }
        CompanionState.update { it.copy(status = "Speaking...") }
    }

    private fun onUtteranceFinished() {
        if (!active) return
        pending--
        checkFinish()
    }

    private fun checkFinish() {
        if (turnOpen && streamDone && pending <= 0) {
            turnOpen = false
            abandonFocus()
            awakeUntil = SystemClock.elapsedRealtime() + prefs.followUp * 1000L
            main.postDelayed({ startListening() }, 400)
        }
    }

    // ---------- Audio focus ----------
    private fun requestFocus() {
        if (hasFocus) return
        val type = if (prefs.pause) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val req = AudioFocusRequest.Builder(type)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { _ -> }
            .build()
        focusReq = req
        audio.requestAudioFocus(req)
        hasFocus = true
    }

    private fun abandonFocus() {
        focusReq?.let { audio.abandonAudioFocusRequest(it) }
        focusReq = null
        hasFocus = false
    }

    // ---------- Speech recognition ----------
    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { tEnd = SystemClock.elapsedRealtime() }
        override fun onError(error: Int) { handleSttError(error) }
        override fun onResults(results: Bundle?) { handleResults(results) }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun startListening() {
        if (!active) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        }
        tEnd = 0L
        val w = prefs.wake.trim()
        val label = when {
            w.isEmpty() -> "Listening..."
            SystemClock.elapsedRealtime() < awakeUntil -> "Awake - go ahead..."
            else -> "Waiting for \"$w\"..."
        }
        CompanionState.update { it.copy(status = label) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer?.startListening(i)
    }

    private fun handleSttError(error: Int) {
        if (!active) return
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("Microphone permission missing")
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                main.postDelayed({ startListening() }, 200)
            else -> {
                CompanionState.update { it.copy(error = "Speech recogniser error $error, retrying") }
                recognizer?.destroy()
                recognizer = null
                main.postDelayed({ startListening() }, 1000)
            }
        }
    }

    private fun handleResults(results: Bundle?) {
        if (!active) return
        tResults = SystemClock.elapsedRealtime()
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) { startListening(); return }

        val wake = prefs.wake.trim()
        if (wake.isEmpty()) { respond(text); return }

        val now = SystemClock.elapsedRealtime()
        val hasWake = text.contains(wake, ignoreCase = true)
        if (!hasWake && now >= awakeUntil) {
            CompanionState.update { it.copy(heard = "(ignored) $text") }
            startListening()
            return
        }
        val stripped = text
            .replace(Regex(Regex.escape(wake), RegexOption.IGNORE_CASE), "")
            .trim { it in " ,.!?:;-" }
        if (stripped.isEmpty()) {
            // Wake phrase on its own: chime, then wait for the actual question
            awakeUntil = now + 10_000
            try {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_ACK, 120)
            } catch (e: Exception) { }
            CompanionState.update { it.copy(heard = text) }
            main.postDelayed({ startListening() }, 300)
            return
        }
        respond(stripped)
    }

    // ---------- Gemini ----------
    private fun respond(text: String) {
        history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        while (history.length() > 20) { history.remove(0); history.remove(0) }

        replyBuf.clear()
        sentenceBuf.clear()
        pending = 0
        streamDone = false
        turnOpen = true
        firstAudioLogged = false
        tSentence = 0L
        CompanionState.update {
            it.copy(heard = text, reply = "", status = "Thinking...", error = "", sttMs = -1, llmMs = -1, ttsMs = -1)
        }

        val snapshot = JSONArray(history.toString())
        val key = prefs.apiKey
        val model = prefs.model
        worker?.execute {
            try {
                GeminiClient.stream(key, model, snapshot) { chunk -> main.post { onChunk(chunk) } }
                main.post { onStreamEnd() }
            } catch (e: Exception) {
                main.post { onStreamError(e) }
            }
        }
    }

    private fun onChunk(chunk: String) {
        if (!active) return
        replyBuf.append(chunk)
        sentenceBuf.append(chunk)
        CompanionState.update { it.copy(reply = replyBuf.toString()) }
        drain(false)
    }

    private fun drain(flush: Boolean) {
        val parts = boundary.split(sentenceBuf.toString())
        sentenceBuf.clear()
        val complete = if (flush) parts else parts.dropLast(1)
        if (!flush) sentenceBuf.append(parts.last())
        for (p in complete) speak(p)
    }

    private fun onStreamEnd() {
        if (!active) return
        drain(true)
        if (replyBuf.isBlank()) {
            history.remove(history.length() - 1)
            speak("Sorry, I didn't get an answer.")
        } else {
            history.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", replyBuf.toString()))))
        }
        streamDone = true
        checkFinish()
    }

    private fun onStreamError(e: Exception) {
        if (!active) return
        history.remove(history.length() - 1)
        CompanionState.update { it.copy(error = e.message ?: "Unknown error") }
        speak("Sorry, I couldn't reach Gemini.")
        streamDone = true
        checkFinish()
    }

    // ---------- Lifecycle ----------
    private fun fail(msg: String) {
        CompanionState.update { it.copy(error = msg) }
        shutdown()
    }

    private fun shutdown() {
        active = false
        turnOpen = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        abandonFocus()
        worker?.shutdownNow()
        worker = null
        CompanionState.update { it.copy(running = false, status = "Stopped") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (active) shutdown()
        super.onDestroy()
    }
}
