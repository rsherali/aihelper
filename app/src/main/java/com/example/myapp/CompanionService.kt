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
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
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
        private const val ARM_MS = 10_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var worker: ExecutorService? = null
    private var session: MediaSession? = null
    private var active = false
    private var listening = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var focusReq: AudioFocusRequest? = null
    private var hasFocus = false

    private val buttonKeys = setOf(
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE
    )
    private val callModes = setOf(
        AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION
    )
    private val stopPhrases = setOf(
        "stop", "stop it", "that's all", "thats all", "never mind", "nevermind",
        "cancel", "be quiet", "quiet", "enough"
    )

    private val history = JSONArray()
    private val replyBuf = StringBuilder()
    private val sentenceBuf = StringBuilder()
    private val boundary = Regex("(?<=[.!?])\\s+|\\n+")
    private var pending = 0
    private var streamDone = false
    private var turnOpen = false
    private var replyCommitted = true
    @Volatile private var turnId = 0
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
        worker = Executors.newCachedThreadPool()
        CompanionState.update { UiState(running = true, status = "Starting...") }
        if (prefs.buttonTrigger) startMediaSession()
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

    // ---------- Earbud / headset button ----------
    private fun startMediaSession() {
        val s = MediaSession(this, "CompanionSession")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ev = IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                if (ev != null && ev.keyCode in buttonKeys) {
                    if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) main.post { onButton() }
                    return true
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build()
        )
        s.isActive = true
        session = s
    }

    private fun onButton() {
        if (!active) return
        if (turnOpen) interrupt()   // thinking or speaking: cut it off
        arm()                       // then listen for the next thing, no wake phrase needed
    }

    private fun arm() {
        awakeUntil = SystemClock.elapsedRealtime() + ARM_MS
        chime()
        main.postDelayed({ startListening() }, 300)
    }

    private fun chime() {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (e: Exception) { }
    }

    // ---------- Interrupt ----------
    private fun interrupt() {
        turnId++                       // invalidates the in-flight stream and queued speech
        tts?.stop()
        pending = 0
        if (turnOpen && !replyCommitted) {
            if (replyBuf.isBlank()) removeLastHistory() else commitReply()
            replyCommitted = true
        }
        turnOpen = false
        streamDone = true
        abandonFocus()
        CompanionState.update { it.copy(status = "Interrupted") }
    }

    private fun removeLastHistory() {
        if (history.length() > 0) history.remove(history.length() - 1)
    }

    private fun commitReply() {
        history.put(JSONObject().put("role", "model")
            .put("parts", JSONArray().put(JSONObject().put("text", replyBuf.toString()))))
    }

    // ---------- TTS ----------
    private fun onTtsInit(status: Int) {
        if (!active) return
        if (status != TextToSpeech.SUCCESS) { fail("Text-to-speech failed to start"); return }
        tts?.language = Locale.getDefault()
        tts?.setAudioAttributes(attrs)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { main.post { onSpeechStarted(utteranceId) } }
            override fun onDone(utteranceId: String?) { main.post { onUtteranceFinished(utteranceId) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { onUtteranceFinished(utteranceId) } }
        })
        startListening()
    }

    private fun isCurrent(id: String?): Boolean = id?.substringBefore(":")?.toIntOrNull() == turnId

    private fun speak(text: String) {
        val clean = text.replace(Regex("[*#`]"), "").trim()
        if (clean.isEmpty()) return
        if (tSentence == 0L) tSentence = SystemClock.elapsedRealtime()
        requestFocus()
        pending++
        tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, "$turnId:" + (utteranceCounter++))
    }

    private fun onSpeechStarted(id: String?) {
        if (!active || !isCurrent(id)) return
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

    private fun onUtteranceFinished(id: String?) {
        if (!active || !isCurrent(id)) return
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
            .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, main)
            .build()
        focusReq = req
        audio.requestAudioFocus(req)
        hasFocus = true
    }

    private fun onFocusChange(change: Int) {
        // Something else (e.g. a phone call) took the audio: stop talking, then wait
        if (!active || !hasFocus) return
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            interrupt()
            main.postDelayed({ startListening() }, 500)
        }
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
        if (!active || listening || turnOpen) return
        if (audio.mode in callModes) {
            CompanionState.update { it.copy(status = "Paused: call in progress") }
            main.postDelayed({ startListening() }, 2000)
            return
        }
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
        listening = true
        recognizer?.startListening(i)
    }

    private fun handleSttError(error: Int) {
        listening = false
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

    private fun isStopPhrase(t: String): Boolean {
        val norm = t.lowercase().replace('\u2019', '\'').trim { !it.isLetterOrDigit() }
        return norm in stopPhrases
    }

    private fun handleResults(results: Bundle?) {
        listening = false
        if (!active) return
        tResults = SystemClock.elapsedRealtime()
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) { startListening(); return }

        val wake = prefs.wake.trim()
        val now = SystemClock.elapsedRealtime()
        val hasWake = wake.isNotEmpty() && text.contains(wake, ignoreCase = true)
        if (wake.isNotEmpty() && !hasWake && now >= awakeUntil) {
            CompanionState.update { it.copy(heard = "(ignored) $text") }
            startListening()
            return
        }
        val core = if (hasWake) {
            text.replace(Regex(Regex.escape(wake), RegexOption.IGNORE_CASE), "")
                .trim { it in " ,.!?:;-" }
        } else text

        if (isStopPhrase(core)) {            // "stop", "that's all" ends the conversation window
            awakeUntil = 0L
            CompanionState.update { it.copy(heard = text) }
            startListening()
            return
        }
        if (core.isEmpty()) {                // wake phrase on its own: wait for the question
            CompanionState.update { it.copy(heard = text) }
            arm()
            return
        }
        respond(core)
    }

    // ---------- Gemini ----------
    private fun respond(text: String) {
        history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        while (history.length() > 20) { history.remove(0); history.remove(0) }

        turnId++
        val myTurn = turnId
        replyBuf.clear()
        sentenceBuf.clear()
        pending = 0
        streamDone = false
        turnOpen = true
        replyCommitted = false
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
                GeminiClient.stream(key, model, snapshot, { myTurn != turnId }) { chunk ->
                    main.post { onChunk(myTurn, chunk) }
                }
                main.post { onStreamEnd(myTurn) }
            } catch (e: Exception) {
                main.post { onStreamError(myTurn, e) }
            }
        }
    }

    private fun onChunk(id: Int, chunk: String) {
        if (!active || id != turnId) return
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

    private fun onStreamEnd(id: Int) {
        if (!active || id != turnId) return
        drain(true)
        if (replyBuf.isBlank()) {
            removeLastHistory()
            speak("Sorry, I didn't get an answer.")
        } else {
            commitReply()
        }
        replyCommitted = true
        streamDone = true
        checkFinish()
    }

    private fun onStreamError(id: Int, e: Exception) {
        if (!active || id != turnId) return
        removeLastHistory()
        replyCommitted = true
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
        listening = false
        main.removeCallbacksAndMessages(null)
        session?.isActive = false
        session?.release()
        session = null
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
