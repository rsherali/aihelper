package com.example.myapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
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
        const val ACTION_ASK = "com.example.myapp.ASK"
        private const val CHANNEL = "companion"
        private const val NOTIF_ID = 1
        private const val ASK_WAIT_MS = 8_000L
        private const val REFRESH_MS = 15_000L
        private const val READY = "Ready - press your earbud button"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private var tts: TextToSpeech? = null
    private var worker: ExecutorService? = null
    private var session: MediaSession? = null
    private var silent: AudioTrack? = null
    private var active = false
    private var capturing = false   // recording a user utterance
    private var watching = false    // listening for the user to talk over a reply

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
    private val rawBuf = StringBuilder()
    private val replyBuf = StringBuilder()
    private val sentenceBuf = StringBuilder()
    private val boundary = Regex("(?<=[.!?])\\s+|\\n+")
    private var heardText = ""
    private var transcriptDone = false
    private var pending = 0
    private var streamDone = false
    private var turnOpen = false
    private var replyCommitted = true
    @Volatile private var replyId = 0
    @Volatile private var capId = 0
    private var utteranceCounter = 0
    private var firstAudioLogged = false
    private var tCaptured = 0L
    private var tSentence = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_ASK -> { if (active) main.post { onButton() }; return START_NOT_STICKY }
        }
        if (active) return START_NOT_STICKY

        prefs = Prefs(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        startInForeground()

        if (prefs.apiKey.isBlank()) { fail("Add your Gemini API key first"); return START_NOT_STICKY }

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
        val ask = PendingIntent.getService(
            this, 1, Intent(this, CompanionService::class.java).setAction(ACTION_ASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Companion is on")
            .setContentText("Press your earbud button or tap Ask")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Ask", ask)
            .addAction(0, "Stop", stop)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    // ---------- Earbud / headset button ----------
    private fun pbState(st: Int): PlaybackState =
        PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(st, 0L, 1f)
            .build()

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
        s.setPlaybackState(pbState(PlaybackState.STATE_PLAYING))
        s.isActive = true
        session = s
        startSilent()
    }

    // Android sends the headset button to the app that most recently played audio, so we keep
    // a silent looping track and periodically re-assert ourselves while idle.
    private fun startSilent() {
        stopSilent()
        try {
            val rate = 8000
            val bytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(2048)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(ByteArray(bytes), 0, bytes)
            t.setLoopPoints(0, bytes / 2, -1)
            t.play()
            silent = t
        } catch (e: Exception) { }
    }

    private fun stopSilent() {
        try { silent?.stop() } catch (e: Exception) { }
        try { silent?.release() } catch (e: Exception) { }
        silent = null
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!active) return
            if (session != null && !turnOpen && !capturing && !watching) {
                session?.setPlaybackState(pbState(PlaybackState.STATE_PAUSED))
                session?.setPlaybackState(pbState(PlaybackState.STATE_PLAYING))
                startSilent()
            }
            main.postDelayed(this, REFRESH_MS)
        }
    }

    private fun onButton() {
        if (!active) return
        if (capturing) { chime(); return }          // already recording your question
        if (turnOpen || watching) interrupt()       // thinking or speaking: cut it off
        chime()
        main.postDelayed({ startCapture(ASK_WAIT_MS) }, 300)
    }

    private fun chime() {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (e: Exception) { }
    }

    // ---------- Capture (own mic capture: no audio focus, so music is not disturbed) ----------
    private fun headphonesConnected(): Boolean {
        val types = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET
        )
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    /** watch = true: listen while a reply is playing; real speech cuts the reply off and becomes your next question. */
    private fun startCapture(waitMs: Long, watch: Boolean = false) {
        if (!active || capturing) return
        if (!watch && turnOpen) return
        if (audio.mode in callModes) {
            if (!watch) CompanionState.update { it.copy(status = "Paused: call in progress") }
            return
        }
        capId++
        val myCap = capId
        if (watch) {
            watching = true
        } else {
            watching = false
            capturing = true
            CompanionState.update { it.copy(status = "Listening...", error = "") }
        }
        worker?.execute {
            try {
                val wav = Recorder.capture(
                    waitMs,
                    { myCap != capId || !active },
                    { main.post { onBargeDetected(myCap) } }
                )
                main.post { onCaptured(myCap, wav) }
            } catch (e: Exception) {
                main.post { onCaptureError(myCap, e) }
            }
        }
    }

    private fun onBargeDetected(id: Int) {
        if (!active || id != capId || !watching) return
        watching = false
        capturing = true
        stopReply()
        CompanionState.update { it.copy(status = "Listening...") }
    }

    private fun onCaptured(id: Int, wav: ByteArray?) {
        if (!active || id != capId) return
        val wasWatching = watching
        capturing = false
        watching = false
        if (wav == null || wasWatching) {
            if (!turnOpen) CompanionState.update { it.copy(status = READY) }
            return
        }
        tCaptured = SystemClock.elapsedRealtime()
        respondAudio(wav)
    }

    private fun onCaptureError(id: Int, e: Exception) {
        if (!active || id != capId) return
        capturing = false
        watching = false
        CompanionState.update { it.copy(error = e.message ?: "Capture failed", status = READY) }
    }

    // ---------- Interrupt / abort ----------
    /** Stops the current reply (speech and stream) but leaves any capture running. */
    private fun stopReply() {
        replyId++
        tts?.stop()
        pending = 0
        if (turnOpen && !replyCommitted && replyBuf.isNotBlank()) commitTurn()
        replyCommitted = true
        turnOpen = false
        streamDone = true
        abandonFocus()
    }

    private fun interrupt() {
        stopReply()
        capId++
        capturing = false
        watching = false
        CompanionState.update { it.copy(status = "Interrupted") }
    }

    private fun abort(status: String) {
        stopReply()
        capId++
        capturing = false
        watching = false
        CompanionState.update { it.copy(status = "$status - press your earbud button") }
    }

    private fun msg(role: String, text: String): JSONObject =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun commitTurn() {
        history.put(msg("user", "(spoken audio)"))
        history.put(msg("model", "HEARD: $heardText\n$replyBuf"))
        while (history.length() > 20) { history.remove(0); history.remove(0) }
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
        CompanionState.update { it.copy(status = READY) }
        main.postDelayed(refresh, REFRESH_MS)
    }

    private fun isCurrent(id: String?): Boolean = id?.substringBefore(":")?.toIntOrNull() == replyId

    private fun speak(text: String) {
        val clean = text.replace(Regex("[*#`]"), "").trim()
        if (clean.isEmpty()) return
        if (tSentence == 0L) tSentence = SystemClock.elapsedRealtime()
        requestFocus()
        pending++
        tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, "$replyId:" + (utteranceCounter++))
    }

    private fun onSpeechStarted(id: String?) {
        if (!active || !isCurrent(id)) return
        if (!firstAudioLogged) {
            firstAudioLogged = true
            val now = SystemClock.elapsedRealtime()
            val llm = tSentence - tCaptured
            val ttsMs = now - tSentence
            CompanionState.update { it.copy(sttMs = -1, llmMs = llm, ttsMs = ttsMs) }
            if (prefs.bargeIn && headphonesConnected()) startCapture(60_000L, watch = true)
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
            CompanionState.update { it.copy(status = READY) }
            if (watching) { capId++; watching = false }
            val follow = prefs.followUp
            if (follow > 0) main.postDelayed({ startCapture(follow * 1000L) }, 400)
        }
    }

    // ---------- Audio focus (only while we speak) ----------
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
        // Something else (e.g. a phone call) took the audio: stop talking
        if (!active || !hasFocus) return
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            interrupt()
        }
    }

    private fun abandonFocus() {
        focusReq?.let { audio.abandonAudioFocusRequest(it) }
        focusReq = null
        hasFocus = false
    }

    // ---------- Gemini (audio in, streamed text out) ----------
    private fun isStopPhrase(t: String): Boolean {
        val norm = t.lowercase().replace('\u2019', '\'').trim { !it.isLetterOrDigit() }
        return norm in stopPhrases
    }

    private fun respondAudio(wav: ByteArray) {
        replyId++
        rawBuf.clear()
        replyBuf.clear()
        sentenceBuf.clear()
        heardText = ""
        transcriptDone = false
        pending = 0
        streamDone = false
        turnOpen = true
        replyCommitted = false
        firstAudioLogged = false
        tSentence = 0L
        CompanionState.update {
            it.copy(heard = "", reply = "", status = "Thinking...", error = "", sttMs = -1, llmMs = -1, ttsMs = -1)
        }

        val myTurn = replyId
        val audioTurn = JSONObject().put("role", "user").put(
            "parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "audio/wav")
                    .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
                .put(JSONObject().put("text", "(Answer the audio above using the required format.)"))
        )
        val snapshot = JSONArray(history.toString()).put(audioTurn)
        val key = prefs.apiKey
        val model = prefs.model
        worker?.execute {
            try {
                GeminiClient.stream(key, model, snapshot, { myTurn != replyId }) { chunk ->
                    main.post { onChunk(myTurn, chunk) }
                }
                main.post { onStreamEnd(myTurn) }
            } catch (e: Exception) {
                main.post { onStreamError(myTurn, e) }
            }
        }
    }

    private fun acceptTranscript(line: String) {
        transcriptDone = true
        val t = line.replace(Regex("^\\s*HEARD:\\s*", RegexOption.IGNORE_CASE), "").trim()
        heardText = t
        CompanionState.update { it.copy(heard = t) }
        if (t.isEmpty() || t.startsWith("(nothing", ignoreCase = true)) abort("Didn't catch that")
        else if (isStopPhrase(t)) abort("OK")
    }

    private fun onChunk(id: Int, chunk: String) {
        if (!active || id != replyId) return
        if (!transcriptDone) {
            rawBuf.append(chunk)
            val s = rawBuf.toString()
            val nl = s.indexOf('\n')
            if (nl >= 0) {
                acceptTranscript(s.substring(0, nl))
                if (!active || id != replyId) return
                feedReply(s.substring(nl + 1))
            } else if (s.length > 400 && !s.trimStart().startsWith("HEARD", ignoreCase = true)) {
                transcriptDone = true
                heardText = "(spoken)"
                feedReply(s)
            }
            return
        }
        feedReply(chunk)
    }

    private fun feedReply(text: String) {
        if (text.isEmpty()) return
        replyBuf.append(text)
        sentenceBuf.append(text)
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
        if (!active || id != replyId) return
        if (!transcriptDone) {
            val s = rawBuf.toString().trim()
            if (s.startsWith("HEARD", ignoreCase = true)) {
                acceptTranscript(s)
                if (id != replyId) return
            } else {
                transcriptDone = true
                heardText = "(spoken)"
                feedReply(s)
            }
        }
        drain(true)
        if (replyBuf.isBlank()) speak("Sorry, I didn't get an answer.") else commitTurn()
        replyCommitted = true
        streamDone = true
        checkFinish()
    }

    private fun onStreamError(id: Int, e: Exception) {
        if (!active || id != replyId) return
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
        capturing = false
        watching = false
        replyId++
        capId++
        main.removeCallbacksAndMessages(null)
        session?.isActive = false
        session?.release()
        session = null
        stopSilent()
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
