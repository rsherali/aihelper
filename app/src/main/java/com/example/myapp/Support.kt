package com.example.myapp

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UiState(
    val running: Boolean = false,
    val status: String = "Idle",
    val heard: String = "",
    val reply: String = "",
    val sttMs: Long = -1,
    val llmMs: Long = -1,
    val ttsMs: Long = -1,
    val error: String = ""
)

object CompanionState {
    val state = MutableStateFlow(UiState())
    fun update(f: (UiState) -> UiState) {
        state.value = f(state.value)
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("companion", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("key", "") ?: ""
        set(v) { sp.edit().putString("key", v).apply() }

    var model: String
        get() = sp.getString("model", "gemini-flash-latest") ?: "gemini-flash-latest"
        set(v) { sp.edit().putString("model", v).apply() }

    // If set, only utterances containing this phrase get an answer
    var wake: String
        get() = sp.getString("wake", "") ?: ""
        set(v) { sp.edit().putString("wake", v).apply() }

    // seconds to stay awake after a reply, so follow-ups need no wake phrase (0 = off)
    var followUp: Int
        get() = sp.getInt("followup", 10)
        set(v) { sp.edit().putInt("followup", v).apply() }

    // earbud/headset button = ask now, or interrupt while it is speaking
    var buttonTrigger: Boolean
        get() = sp.getBoolean("button", true)
        set(v) { sp.edit().putBoolean("button", v).apply() }

    // talk over it to interrupt (only used when headphones are connected)
    var bargeIn: Boolean
        get() = sp.getBoolean("barge", true)
        set(v) { sp.edit().putBoolean("barge", v).apply() }

    // false = duck other audio, true = pause other audio while speaking
    var pause: Boolean
        get() = sp.getBoolean("pause", false)
        set(v) { sp.edit().putBoolean("pause", v).apply() }
}

object GeminiClient {
    private const val SYSTEM =
        "You are a friendly companion speaking in the user's ear while they go about their day. " +
        "The user's latest message is a short audio clip of them speaking to you. " +
        "OUTPUT FORMAT: the first line must be exactly 'HEARD: ' followed by a verbatim transcript of what they said. " +
        "If there is no clear speech directed at you (silence, music, background noise, other people), output only 'HEARD: (nothing)'. " +
        "Otherwise put your spoken reply after a newline: one to three short, natural sentences, no markdown, lists or emojis. " +
        "If the user changes subject, follow them. Be direct and accurate, and say so if unsure. " +
        "Earlier turns appear in the same HEARD format; keep using it."

    /** Streams the reply; calls onText for each text chunk. Throws on failure. */
    fun stream(apiKey: String, model: String, history: JSONArray, isCancelled: () -> Boolean, onText: (String) -> Unit) {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.doOutput = true
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                .put("contents", history)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw RuntimeException("Gemini HTTP $code: " + err.take(300))
            }
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (isCancelled()) break
                    if (!line.startsWith("data:")) continue
                    val json = line.removePrefix("data:").trim()
                    if (json.isEmpty()) continue
                    val parts = JSONObject(json)
                        .optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val t = parts.optJSONObject(i)?.optString("text", "") ?: ""
                        if (t.isNotEmpty()) onText(t)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** Captures one utterance from the phone mic with simple energy-based voice detection. Does NOT take audio focus. */
object Recorder {
    private const val RATE = 16000
    private const val FRAME = 320 // 20 ms

    /** Blocks; run off the main thread. Returns WAV bytes, or null if no speech began within waitMs. */
    fun capture(waitMs: Long, isCancelled: () -> Boolean, onSpeech: () -> Unit = {}): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 2 * 8)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw RuntimeException("Microphone unavailable")
        }
        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(FRAME)
        val pre = ArrayDeque<ShortArray>()
        var noise = Double.MAX_VALUE
        var threshold = 800.0
        var frames = 0
        var started = false
        var voiced = 0
        var quiet = 0
        var speechFrames = 0
        val waitFrames = (waitMs / 20).toInt()
        try {
            rec.startRecording()
            while (!isCancelled()) {
                val n = rec.read(buf, 0, FRAME)
                if (n < 0) throw RuntimeException("Microphone read error $n")
                if (n == 0) continue
                val level = rms(buf, n)
                frames++
                if (frames <= 15) {
                    noise = minOf(noise, level)
                    if (frames == 15) threshold = (noise * 3.0).coerceIn(400.0, 2000.0)
                }
                if (!started) {
                    pre.addLast(buf.copyOf(n))
                    if (pre.size > 10) pre.removeFirst()
                    if (frames >= 5 && level > threshold) {
                        started = true
                        for (f in pre) write(pcm, f)
                        pre.clear()
                        voiced = 1
                        speechFrames = 1
                    } else if (frames > waitFrames) {
                        return null
                    }
                } else {
                    write(pcm, buf.copyOf(n))
                    speechFrames++
                    if (level > threshold * 0.6) {
                        voiced++
                        quiet = 0
                        if (voiced == 10) onSpeech()   // ~200 ms of speech: real, not a rustle
                    } else quiet++
                    if (quiet >= 45 || speechFrames >= 750) break   // ~0.9 s of quiet, or 15 s max
                }
            }
            if (!started || voiced < 8) return null
            return wav(pcm.toByteArray())
        } finally {
            try { rec.stop() } catch (e: Exception) { }
            rec.release()
        }
    }

    private fun rms(b: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += b[i].toDouble() * b[i].toDouble()
        return Math.sqrt(sum / n)
    }

    private fun write(out: ByteArrayOutputStream, shorts: ShortArray) {
        for (sh in shorts) {
            out.write(sh.toInt() and 0xFF)
            out.write((sh.toInt() shr 8) and 0xFF)
        }
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun i32(v: Int) { out.write(v and 255); out.write((v shr 8) and 255); out.write((v shr 16) and 255); out.write((v shr 24) and 255) }
        fun i16(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
