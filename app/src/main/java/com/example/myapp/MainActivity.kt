package com.example.myapp

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { CompanionScreen() } }
    }
}

@Composable
fun CompanionScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val ui by CompanionState.state.collectAsState()
    val version = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" }

    var key by remember { mutableStateOf(prefs.apiKey) }
    var model by remember { mutableStateOf(prefs.model) }
    var wake by remember { mutableStateOf(prefs.wake) }
    var followUp by remember { mutableStateOf(prefs.followUp.toString()) }
    var pause by remember { mutableStateOf(prefs.pause) }

    fun save() {
        prefs.apiKey = key.trim()
        prefs.model = model.trim()
        prefs.wake = wake.trim()
        prefs.followUp = followUp.toIntOrNull() ?: 10
        prefs.pause = pause
    }

    fun startService() {
        save()
        ContextCompat.startForegroundService(ctx, Intent(ctx, CompanionService::class.java))
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) startService()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Companion (spike) v$version", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { Text("Gemini API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it },
            label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = wake, onValueChange = { wake = it },
            label = { Text("Wake phrase (blank = answer everything)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = followUp, onValueChange = { followUp = it },
            label = { Text("Follow-up window, seconds (0 = off)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = pause, onCheckedChange = { pause = it })
            Text(if (pause) "Pause other audio while speaking" else "Duck other audio while speaking")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (ui.running) {
                    ctx.startService(Intent(ctx, CompanionService::class.java).setAction(CompanionService.ACTION_STOP))
                } else {
                    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                    launcher.launch(perms.toTypedArray())
                }
            }
        ) { Text(if (ui.running) "Stop companion" else "Start companion") }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        ) { Text("Battery settings (set this app to Unrestricted)") }

        Text("Status: ${ui.status}", style = MaterialTheme.typography.titleMedium)
        if (ui.heard.isNotEmpty()) Text("Heard: ${ui.heard}")
        if (ui.reply.isNotEmpty()) Text("Reply: ${ui.reply}")
        if (ui.ttsMs >= 0) {
            val total = (if (ui.sttMs >= 0) ui.sttMs else 0) + ui.llmMs + ui.ttsMs
            Text("Latency: speech-recognition ${ui.sttMs} ms | Gemini ${ui.llmMs} ms | voice start ${ui.ttsMs} ms | total ~$total ms")
        }
        if (ui.error.isNotEmpty()) Text("Error: ${ui.error}", color = MaterialTheme.colorScheme.error)
    }
}
