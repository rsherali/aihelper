package com.example.myapp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
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

    // false = duck other audio, true = pause other audio while speaking
    var pause: Boolean
        get() = sp.getBoolean("pause", false)
        set(v) { sp.edit().putBoolean("pause", v).apply() }
}

object GeminiClient {
    private const val SYSTEM =
        "You are a friendly companion speaking in the user's ear while they go about their day. " +
        "Reply in one to three short, natural spoken sentences. No markdown, lists or emojis. " +
        "If the user changes subject, follow them. Be direct and accurate, and say so if unsure."

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
