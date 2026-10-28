package com.example.ui.components

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun VoiceSearchDialog(
    onDismiss: () -> Unit,
    onQueryConfirmed: (String) -> Unit,
    visible: Boolean = true
) {
    val context = LocalContext.current
    var spokenText by rememberSaveable { mutableStateOf("") }
    var initialStartRequested by rememberSaveable { mutableStateOf(false) }
    var voiceState by remember { mutableStateOf(VoiceRecognitionState(status = if (initialStartRequested)
        "Microphone paused. Tap the microphone to retry, or type your query." else "Starting microphone…")) }
    val manager = remember(context) { VoiceRecognitionManager(context, { voiceState = it }, { spokenText = it }) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visibleNow by rememberUpdatedState(visible)
    var active by remember { mutableStateOf(true) }
    var permissionUseDefault by rememberSaveable { mutableStateOf(false) }
    val systemLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        manager.acceptSystemResult(systemSpeechResult(result.resultCode, result.data))
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (active && visibleNow && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (granted) manager.start(permissionUseDefault) else manager.permissionDenied()
        }
    }
    fun requestListening(useDefault: Boolean = false) {
        if (!visibleNow || !active) return
        if (!manager.hasMicrophoneService()) manager.start()
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) manager.start(useDefault)
        else {
            permissionUseDefault = useDefault
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // One automatic start per opening. Unlock/resume never restarts recording.
    LaunchedEffect(Unit) {
        if (!initialStartRequested) {
            initialStartRequested = true
            requestListening()
        } else if (!manager.hasMicrophoneService()) manager.start()
    }
    LaunchedEffect(visible) { if (!visible) manager.pause() }
    DisposableEffect(lifecycle, manager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) manager.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            active = false
            lifecycle.removeObserver(observer)
            manager.dispose()
        }
    }
    if (!visible) return
    val isListening = voiceState.listening
    val microphoneEnabled = voiceState.microphoneAvailable && !voiceState.checking && !voiceState.downloading

    val pulseScale by rememberInfiniteTransition(label = "mic_pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "English Voice Search",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .testTag("voice_microphone_toggle")
                        .clickable(enabled = microphoneEnabled, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (isListening) "Stop recording" else "Start recording") {
                            if (isListening) manager.stop() else requestListening()
                        }
                        .scale(pulseScale)
                        .background(
                            if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = if (!voiceState.microphoneAvailable) "Voice search unavailable" else if (isListening) "Stop recording" else "Start recording",
                        tint = if (isListening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = voiceState.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (voiceState.checking || voiceState.downloading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("voice_progress"))
                    Spacer(Modifier.height(8.dp))
                }
                if (voiceState.downloadAvailable) {
                    TextButton(onClick = manager::downloadEnglishModel, modifier = Modifier.testTag("voice_download_model")) { Text("Download English voice model") }
                }
                if (voiceState.defaultAvailable) {
                    TextButton(onClick = { requestListening(useDefault = true) }, modifier = Modifier.testTag("voice_use_default")) { Text("Use default voice service (may use internet)") }
                }
                if (voiceState.systemAvailable) {
                    TextButton(onClick = {
                        manager.prepareSystemRecognition()?.let { intent ->
                            try { systemLauncher.launch(intent) }
                            catch (_: android.content.ActivityNotFoundException) { manager.systemLaunchFailed() }
                            catch (_: SecurityException) { manager.systemLaunchFailed() }
                        }
                    }, modifier = Modifier.testTag("voice_use_system")) { Text("Use system voice search (may use internet)") }
                }

                OutlinedTextField(
                    value = spokenText,
                    onValueChange = { spokenText = it },
                    label = { Text("Search Query") },
                    placeholder = { Text("e.g. Find chemistry grade from last month") },
                    modifier = Modifier.fillMaxWidth().testTag("voice_query_text_field")
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (spokenText.isNotBlank()) {
                                onQueryConfirmed(spokenText)
                                onDismiss()
                            }
                        },
                        enabled = spokenText.isNotBlank(),
                        modifier = Modifier.testTag("submit_voice_search_button")
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Search")
                    }
                }
            }
        }
    }
}

internal fun systemSpeechResult(resultCode: Int, data: Intent?): String? =
    if (resultCode == Activity.RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull { it.isNotBlank() } else null
