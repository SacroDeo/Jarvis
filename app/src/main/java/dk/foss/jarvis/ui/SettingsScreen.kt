package dk.foss.jarvis.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dk.foss.jarvis.assist.JarvisInteractionService
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.voice.AndroidTts
import dk.foss.jarvis.voice.ElevenLabsVoices
import dk.foss.jarvis.voice.ElevenVoice
import dk.foss.jarvis.voice.SimpleEnergyVerifier
import dk.foss.jarvis.voice.SpeakerVerifier
import dk.foss.jarvis.voice.TtsVoice
import dk.foss.jarvis.voice.VoicePreviewPlayer
import dk.foss.jarvis.wake.WakeWordService
import dk.foss.jarvis.worker.WorkerClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop

private const val SETTINGS_TAG = "JarvisSettings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var workerUrl by remember { mutableStateOf("") }
    var workerToken by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(SettingsStore.DEFAULT_MODEL) }
    var elevenKey by remember { mutableStateOf("") }
    var elevenVoice by remember { mutableStateOf(SettingsStore.DEFAULT_ELEVEN_VOICE) }
    fun isBatteryExempt(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    var wakeEnabled by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt()) }
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    var voices by remember { mutableStateOf<List<ElevenVoice>>(emptyList()) }
    var voicesLoading by remember { mutableStateOf(false) }
    var voicesError by remember { mutableStateOf<String?>(null) }
    var voicesExpanded by remember { mutableStateOf(false) }
    val previewPlayer = remember { VoicePreviewPlayer(context) }
    var previewRefresh by remember { mutableStateOf(0) }
    var voicesAutoLoaded by remember { mutableStateOf(false) }

    // Whether Jarvis's VoiceInteractionService is the currently selected system assistant.
    // Checked via VoiceInteractionService.isActiveService() — the official public API that
    // reads Settings.Secure.VOICE_INTERACTION_SERVICE directly. This is more accurate than
    // RoleManager.isRoleHeld(ROLE_ASSISTANT): the two are distinct concepts on Android.
    var isDefaultAssistant by remember { mutableStateOf(false) }

// TTS voice selection (Android voices)
    var ttsVoices by remember { mutableStateOf<List<TtsVoice>>(emptyList()) }
    var ttsVoicesLoading by remember { mutableStateOf(false) }
    var ttsVoicesError by remember { mutableStateOf<String?>(null) }
    var ttsVoicesExpanded by remember { mutableStateOf(false) }
    var selectedTtsVoice by remember { mutableStateOf<TtsVoice?>(null) }
    var ttsVoicesAutoLoaded by remember { mutableStateOf(false) }

    // Speaker verification
    var speakerVerificationEnabled by remember { mutableStateOf(false) }
    var speakerVerificationLoading by remember { mutableStateOf(false) }
    var speakerEnrolling by remember { mutableStateOf(false) }
    var speakerEnrollmentMessage by remember { mutableStateOf<String?>(null) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { overlayGranted = AndroidSettings.canDrawOverlays(context) }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { batteryExempt = isBatteryExempt() }

    fun requestOverlay() {
        overlayLauncher.launch(
            Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
        )
    }

    fun requestBattery() {
        batteryLauncher.launch(
            Intent(
                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }

    /**
     * Synchronously check whether Jarvis's VoiceInteractionService is the system-selected
     * assistant and update [isDefaultAssistant].
     *
     * VoiceInteractionService.isActiveService() is a fast, synchronous read of
     * Settings.Secure.VOICE_INTERACTION_SERVICE — no coroutine needed.
     */
    fun checkDefaultAssistant() {
        val active = JarvisInteractionService.isActiveService(context)
        Log.i(SETTINGS_TAG, "checkDefaultAssistant: isActiveVIS=$active")
        isDefaultAssistant = active
    }

    // Launched when the user taps "Set as default assistant". Uses StartActivityForResult so
    // we are notified when the system dialog closes and can immediately recheck VIS status.
    // Defined AFTER checkDefaultAssistant so the callback lambda can reference the local function
    // (Kotlin does not allow forward references to local functions inside lambda captures).
    val assistantRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val code = result.resultCode
        Log.i(SETTINGS_TAG, "assistantRoleLauncher returned resultCode=$code (RESULT_OK=${Activity.RESULT_OK})")
        // Recheck VIS status regardless of result code.
        checkDefaultAssistant()
        if (!isDefaultAssistant) {
            // The ROLE_ASSISTANT dialog either returned RESULT_CANCELED immediately (silently,
            // no UI shown) or the user cancelled. Either way, Jarvis is still not the active VIS.
            // Guide the user directly to Default Apps → "Digital assistant app".
            Log.i(SETTINGS_TAG, "Not yet active VIS after role result — opening Default Apps settings")
            openAssistantSettings(context)
        }
    }

    /**
     * Request the Android ROLE_ASSISTANT role for Jarvis. This shows a system chooser dialog
     * on stock Android 10+ where the user can select Jarvis as the default digital assistant.
     * On OEM builds where the dialog is suppressed or ROLE_ASSISTANT is restricted, the result
     * callback falls through to [openAssistantSettings] which opens Default Apps settings.
     */
    fun requestDefaultAssistant() {
        Log.i(SETTINGS_TAG, "requestDefaultAssistant: button tapped")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as android.app.role.RoleManager
                val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT)
                Log.i(SETTINGS_TAG, "requestDefaultAssistant: launching ROLE_ASSISTANT intent=$intent")
                assistantRoleLauncher.launch(intent)
                // Execution continues in the assistantRoleLauncher callback above.
                // If the system dialog shows and the user selects Jarvis → RESULT_OK → VIS becomes active.
                // If the dialog is suppressed (OEM restriction) → RESULT_CANCELED → callback opens Settings.
            } catch (e: Exception) {
                Log.w(SETTINGS_TAG, "requestDefaultAssistant: createRequestRoleIntent/launch failed — ${e.message}", e)
                // Role request not possible on this device — go straight to Settings.
                openAssistantSettings(context)
            }
        } else {
            // minSdk is 29 so this branch is dead code, but kept for completeness.
            openAssistantSettings(context)
        }
    }

    suspend fun persist() {
        store.updateWorkerConnection(workerUrl, workerToken, model)
        store.updateVoice(elevenKey, elevenVoice)
        // Drop any legacy auto-populated voice so it can't override the phone's
        // system Text-to-speech voice, then persist an explicit choice if there is one.
        store.migrateLegacyTtsVoice()
        if (selectedTtsVoice != null) {
            store.updateTtsVoice(selectedTtsVoice)
        }
        store.updateSpeakerVerification(speakerVerificationEnabled)
    }

    fun loadTtsVoices() {
        ttsVoicesLoading = true
        ttsVoicesError = null
        scope.launch {
            try {
                val voices = AndroidTts.getAvailableVoices(context)
                ttsVoices = voices
                // Deliberately no auto-selection here.  Saving a voice we picked
                // ourselves would override the voice configured in Android's
                // Text-to-speech settings; only an explicit user tap may persist one.
            } catch (e: Exception) {
                ttsVoicesError = e.message ?: "Failed to load voices"
            } finally {
                ttsVoicesLoading = false
            }
        }
    }

    fun testTtsVoice(voice: TtsVoice) {
        scope.launch {
            val testTts = AndroidTts(context, voice = voice)
            testTts.applyVoiceIfNeeded()
            // Wait a bit for init
            Thread.sleep(300)
            testTts.speak(
                "Hello, this is Jarvis speaking.",
                onDone = { testTts.shutdown() },
                onError = { msg -> testTts.shutdown() }
            )
        }
    }

    fun checkEnrolled(): Boolean {
        val profileDir = java.io.File(context.filesDir, "speaker_profile")
        return profileDir.exists() && profileDir.listFiles()?.isNotEmpty() == true
    }

    fun startEnrollment() {
        speakerEnrolling = true
        speakerEnrollmentMessage = "Starting enrollment…"
        scope.launch {
            val verifier = SimpleEnergyVerifier(context)
            verifier.enroll(object : SpeakerVerifier.EnrollmentListener {
                override fun onPhraseRequested(phraseIndex: Int, totalPhrases: Int, phrase: String) {
                    speakerEnrollmentMessage = "Phrase ${phraseIndex + 1}/$totalPhrases: \"$phrase\""
                }

                override fun onRecordingComplete(phraseIndex: Int) {
                    speakerEnrollmentMessage = "Recorded phrase ${phraseIndex + 1}"
                }

                override fun onEnrollmentComplete(success: Boolean, message: String) {
                    speakerEnrolling = false
                    speakerEnrollmentMessage = message
                }
            })
        }
    }

    fun deleteSpeakerProfile() {
        scope.launch {
            val verifier = SimpleEnergyVerifier(context)
            verifier.deleteProfile()
            speakerEnrollmentMessage = "Speaker profile deleted"
            if (speakerVerificationEnabled) {
                speakerVerificationEnabled = false
                store.updateSpeakerVerification(false)
            }
        }
    }

    fun toggleSpeakerVerification(on: Boolean) {
        speakerVerificationEnabled = on
        scope.launch { store.updateSpeakerVerification(on) }
        if (on && !checkEnrolled()) {
            startEnrollment()
        }
    }

    fun enableWake() {
        scope.launch { store.updateWake(true) }
        wakeEnabled = true
        // If Jarvis is the default assistant, VoiceInteractionService.onReady() will start
        // the wake word listener automatically. Otherwise, fall back to legacy start.
        if (JarvisInteractionService.isActiveService(context)) {
            Log.d(SETTINGS_TAG, "Jarvis is default assistant — VoiceInteractionService owns wake listener")
        } else {
            Log.d(SETTINGS_TAG, "Jarvis is NOT default assistant — starting legacy wake listener")
            WakeWordService.start(context.applicationContext)
        }
        if (!overlayGranted) requestOverlay() else if (!batteryExempt) requestBattery()
    }

    val wakePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) enableWake()
    }

    fun toggleWake(on: Boolean) {
        if (on) {
            val needed = buildList {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (needed.isEmpty()) enableWake() else wakePermLauncher.launch(needed.toTypedArray())
        } else {
            scope.launch { store.updateWake(false) }
            // If we're the default assistant, VoiceInteractionService.onShutdown() stops the listener
            // Otherwise, stop the legacy service directly
            if (JarvisInteractionService.isActiveService(context)) {
                Log.d(SETTINGS_TAG, "Jarvis is default assistant — VoiceInteractionService will stop wake listener on shutdown")
            } else {
                WakeWordService.stop(context)
            }
            wakeEnabled = false
        }
    }

    LaunchedEffect(Unit) {
        val s = store.settings.first()
        workerUrl = s.workerUrl
        workerToken = s.workerToken
        model = s.model
        elevenKey = s.elevenKey
        elevenVoice = s.elevenVoiceId
        wakeEnabled = s.wakeEnabled
        speakerVerificationEnabled = s.speakerVerificationEnabled
        // Load saved TTS voice — only an explicit user choice is honoured; anything
        // blank or unreadable stays null so AndroidTts uses the system voice.
        s.ttsVoiceUserId?.takeIf { it.isNotBlank() }?.let {
            try {
                selectedTtsVoice = Json { ignoreUnknownKeys = true }.decodeFromString(TtsVoice.serializer(), it)
            } catch (e: Exception) {
                // Ignore invalid saved voice; keep system default.
            }
        }
        loaded = true
    }

    LaunchedEffect(Unit) {
        checkDefaultAssistant()
        loadTtsVoices()
    }

    LaunchedEffect(Unit) {
        previewPlayer.onStateChanged = { previewRefresh++ }
    }

    LaunchedEffect(loaded, elevenKey) {
        if (loaded && elevenKey.isNotBlank() && !voicesAutoLoaded && voices.isEmpty()) {
            voicesAutoLoaded = true
            voicesLoading = true
            voicesError = null
            val result = ElevenLabsVoices(elevenKey).list()
            voicesLoading = false
            result.fold(
                onSuccess = { voices = it },
                onFailure = { voicesError = it.message ?: "Failed to load voices" },
            )
        }
    }

    // Recheck VIS status every time this screen comes back to the foreground.
    // This handles the case where the user navigates to the Android Settings app
    // (via the button or directly), changes the default assistant, and returns.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Log.i(SETTINGS_TAG, "ON_RESUME — rechecking VIS status")
                checkDefaultAssistant()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose { previewPlayer.release() }
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = JarvisColors.Cyan.copy(alpha = 0.5f),
        unfocusedBorderColor = JarvisColors.CyanBorder,
        focusedLabelColor = JarvisColors.Cyan,
        unfocusedLabelColor = JarvisColors.Muted,
        cursorColor = JarvisColors.Cyan,
        focusedTextColor = JarvisColors.TextPrimary,
        unfocusedTextColor = JarvisColors.TextPrimary,
        focusedPlaceholderColor = JarvisColors.Muted,
        unfocusedPlaceholderColor = JarvisColors.Muted,
    )

    DeepSpaceBackground(active = false) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Settings",
                            fontFamily = SpaceGrotesk,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            previewPlayer.stop()
                            scope.launch { persist() }
                            onBack()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = JarvisColors.Cyan,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = JarvisColors.TextPrimary,
                    ),
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader("Worker connection")

                OutlinedTextField(
                    value = workerUrl,
                    onValueChange = { workerUrl = it; status = null },
                    label = { Text("Worker URL") },
                    placeholder = { Text("https://jarvis-worker.example.workers.dev") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = textFieldColors,
                )
                OutlinedTextField(
                    value = workerToken,
                    onValueChange = { workerToken = it; status = null },
                    label = { Text("Bearer token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model (Worker default used)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )

                PillButton(
                    text = "Save & test connection",
                    onClick = {
                        scope.launch {
                            persist()
                            testing = true
                            status = "Testing\u2026"
                            val s = store.settings.first()
                            val result = WorkerClient(s.workerUrl, s.workerToken).testConnection()
                            testing = false
                            status = result.fold(
                                onSuccess = { ids ->
                                    "\u2713 Connected. ${ids.size} model(s)" +
                                        if (ids.isNotEmpty()) ": ${ids.take(5).joinToString()}" else ""
                                },
                                onFailure = { "\u2717 ${it.message}" },
                            )
                        }
                    },
                    accent = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = loaded && !testing && workerUrl.isNotBlank() && workerToken.isNotBlank(),
                )
                if (testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = JarvisColors.Cyan)
                }
                status?.let {
                    Text(
                        it,
                        fontFamily = DmSans,
                        fontSize = 14.sp,
                        color = when {
                            it.startsWith("\u2713") -> JarvisColors.Cyan
                            it.startsWith("Testing") -> JarvisColors.TextSecondary
                            else -> JarvisColors.ErrorOrange
                        },
                    )
                }

SettingsDivider()

                // Android TTS Voice Selection (default, free, on-device)
                SectionHeader("Jarvis voice (on-device)")
                Text(
                    "Select a male English voice from your device's built-in TTS engine. No API key required.",
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.Muted,
                )

                if (ttsVoicesLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        PillButton(
                            text = "Loading…",
                            onClick = { },
                            accent = false,
                            enabled = false,
                        )
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = JarvisColors.Cyan)
                    }
                } else if (ttsVoices.isNotEmpty()) {
                    val englishMaleVoices = ttsVoices.filter { it.locale.startsWith("en") && it.gender == "male" }
                    val otherEnglishVoices = ttsVoices.filter { it.locale.startsWith("en") && it.gender != "male" }
                    val displayVoices = if (englishMaleVoices.isNotEmpty()) englishMaleVoices else otherEnglishVoices

                    ExposedDropdownMenuBox(
                        expanded = ttsVoicesExpanded,
                        onExpandedChange = { ttsVoicesExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedTtsVoice?.let { "$it.name (${it.locale})" } ?: "System default (Android settings)",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Voice") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ttsVoicesExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            colors = textFieldColors,
                        )
                        ExposedDropdownMenu(
                            expanded = ttsVoicesExpanded,
                            onDismissRequest = { ttsVoicesExpanded = false },
                            modifier = Modifier.heightIn(max = 340.dp),
                        ) {
                            // Default option — no in-app voice, AndroidTts uses the phone's system voice
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "System default (Android settings)",
                                        fontFamily = DmSans,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp,
                                        color = JarvisColors.TextPrimary,
                                    )
                                },
                                onClick = {
                                    selectedTtsVoice = null
                                    scope.launch { store.updateTtsVoice(null) }
                                    ttsVoicesExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                            HorizontalDivider()
                            displayVoices.forEach { v ->
                                val isSelected = selectedTtsVoice?.id == v.id
                                DropdownMenuItem(
                                    text = {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "${v.name} (${v.locale})",
                                                fontFamily = DmSans,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 14.sp,
                                                color = JarvisColors.TextPrimary,
                                            )
                                            Text(
                                                "Quality: ${when(v.quality) { 1 -> "High" 2 -> "Normal" else -> "Unknown" }}",
                                                fontFamily = DmSans,
                                                fontSize = 11.sp,
                                                color = JarvisColors.Muted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            IconButton(onClick = { testTtsVoice(v) }) {
                                                Icon(
                                                    Icons.Default.PlayArrow,
                                                    contentDescription = "Test voice",
                                                    tint = JarvisColors.Cyan,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = JarvisColors.Cyan,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        selectedTtsVoice = v
                                        scope.launch { store.updateTtsVoice(v) }
                                        ttsVoicesExpanded = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                )
                            }
                            if (otherEnglishVoices.isNotEmpty() && englishMaleVoices.isNotEmpty()) {
                                HorizontalDivider()
                                Text(
                                    "Other English voices",
                                    fontFamily = DmSans,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = JarvisColors.Muted,
                                    modifier = Modifier.padding(16.dp, 8.dp),
                                )
                                otherEnglishVoices.forEach { v ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${v.name} (${v.locale})",
                                                fontFamily = DmSans,
                                                fontSize = 14.sp,
                                                color = JarvisColors.TextPrimary,
                                            )
                                        },
                                        trailingIcon = {
                                            IconButton(onClick = { testTtsVoice(v) }) {
                                                Icon(
                                                    Icons.Default.PlayArrow,
                                                    contentDescription = "Test voice",
                                                    tint = JarvisColors.Cyan,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedTtsVoice = v
                                            scope.launch { store.updateTtsVoice(v) }
                                            ttsVoicesExpanded = false
                                        },
                                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        "No English voices found. Install a TTS engine (e.g., Google Text-to-Speech) and download an English voice.",
                        fontFamily = DmSans,
                        fontSize = 13.sp,
                        color = JarvisColors.ErrorOrange,
                    )
                    PillButton(
                        text = "Reload voices",
                        onClick = { loadTtsVoices() },
                        accent = false,
                    )
                }
                ttsVoicesError?.let {
                    Text(
                        "\u2717 $it",
                        fontFamily = DmSans,
                        fontSize = 13.sp,
                        color = JarvisColors.ErrorOrange,
                    )
                }

                SettingsDivider()
                SectionHeader("Speaker verification (on-device)")
                Text(
                    "When enabled, Jarvis only responds to your voice after \"Hey Jarvis\". Requires enrollment.",
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.Muted,
                )
                Text(
                    "Note: Current implementation is a placeholder. Real speaker verification requires a speaker embedding model (ECAPA-TDNN, etc.) via TFLite/ONNX.",
                    fontFamily = DmSans,
                    fontSize = 11.sp,
                    color = JarvisColors.ErrorOrange.copy(alpha = 0.8f),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Verify my voice only",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "Only respond to enrolled speaker",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = speakerVerificationEnabled,
                        onCheckedChange = { toggleSpeakerVerification(it) },
                        enabled = !speakerEnrolling,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }

                if (speakerEnrolling) {
                    Text(
                        speakerEnrollmentMessage ?: "Enrolling…",
                        fontFamily = DmSans,
                        fontSize = 13.sp,
                        color = JarvisColors.Cyan,
                    )
                    if (speakerVerificationLoading) {
                        CircularProgressIndicator(Modifier.size(18.dp).fillMaxWidth(), strokeWidth = 2.dp, color = JarvisColors.Cyan)
                    }
                } else if (speakerVerificationEnabled) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        PillButton(
                            text = "Re-enroll voice",
                            onClick = { startEnrollment() },
                            accent = false,
                        )
                        PillButton(
                            text = "Delete profile",
                            onClick = { deleteSpeakerProfile() },
                            accent = false,
                            enabled = true,
                        )
                    }
                }

                SettingsDivider()
                SectionHeader("Optional: ElevenLabs cloud voice")
                Text(
                    "Leave blank to use on-device voice above. Requires ElevenLabs API key for premium speech.",
                    fontFamily = DmSans,
                    fontSize = 13.sp,
                    color = JarvisColors.Muted,
                )
                OutlinedTextField(
                    value = elevenKey,
                    onValueChange = { elevenKey = it },
                    label = { Text("ElevenLabs API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )
                OutlinedTextField(
                    value = elevenVoice,
                    onValueChange = { elevenVoice = it },
                    label = { Text("ElevenLabs voice ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PillButton(
                        text = if (voicesLoading) "Loading\u2026" else "Load voices",
                        onClick = {
                            scope.launch {
                                voicesLoading = true
                                voicesError = null
                                val result = ElevenLabsVoices(elevenKey).list()
                                voicesLoading = false
                                result.fold(
                                    onSuccess = { voices = it },
                                    onFailure = { voicesError = it.message ?: "Failed to load voices" },
                                )
                            }
                        },
                        accent = false,
                        enabled = elevenKey.isNotBlank() && !voicesLoading,
                    )
                    if (voicesLoading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = JarvisColors.Cyan)
                    }
                }
                voicesError?.let {
                    Text(
                        "\u2717 $it",
                        fontFamily = DmSans,
                        fontSize = 13.sp,
                        color = JarvisColors.ErrorOrange,
                    )
                }

                if (elevenKey.isBlank()) {
                    Text(
                        "Enter your ElevenLabs API key to load voices.",
                        fontFamily = DmSans,
                        fontSize = 13.sp,
                        color = JarvisColors.Muted,
                    )
                }

                if (voices.isNotEmpty()) {
                    val previewId = previewPlayer.currentVoiceId
                    val previewing = previewPlayer.isLoading || previewPlayer.isPlaying
                    // Force reads so Compose recomposes on preview state changes
                    @Suppress("UNUSED_VARIABLE") val _r = previewRefresh

                    ExposedDropdownMenuBox(
                        expanded = voicesExpanded,
                        onExpandedChange = { voicesExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = voiceLabel(elevenVoice, voices),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Voice") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voicesExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            colors = textFieldColors,
                        )
                        ExposedDropdownMenu(
                            expanded = voicesExpanded,
                            onDismissRequest = { voicesExpanded = false },
                            modifier = Modifier.heightIn(max = 340.dp),
                        ) {
                            voices.forEach { v ->
                                val label = buildString {
                                    append(v.category.ifEmpty { "voice" })
                                    v.labels["gender"]?.let { append(" \u00b7 "); append(it) }
                                    v.labels["accent"]?.let { append(" \u00b7 "); append(it) }
                                }
                                DropdownMenuItem(
                                    text = {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                v.name,
                                                fontFamily = DmSans,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 14.sp,
                                                color = JarvisColors.TextPrimary,
                                            )
                                            Text(
                                                label,
                                                fontFamily = DmSans,
                                                fontSize = 11.sp,
                                                color = JarvisColors.Muted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        if (v.preview_url != null) {
                                            IconButton(onClick = {
                                                if (previewId == v.voice_id && previewing) {
                                                    previewPlayer.stop()
                                                } else {
                                                    previewPlayer.play(v.voice_id, v.preview_url)
                                                }
                                            }) {
                                                if (previewId == v.voice_id && previewPlayer.isLoading) {
                                                    CircularProgressIndicator(
                                                        Modifier.size(18.dp),
                                                        strokeWidth = 2.dp,
                                                        color = JarvisColors.Cyan,
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = if (previewId == v.voice_id && previewPlayer.isPlaying)
                                                            Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                                        contentDescription = if (previewId == v.voice_id && previewPlayer.isPlaying)
                                                            "Stop" else "Preview",
                                                        tint = JarvisColors.Cyan,
                                                    modifier = Modifier.size(20.dp),
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onClick = {
                                        elevenVoice = v.voice_id
                                        voicesExpanded = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                )
                            }
                        }
                    }
                }

                SettingsDivider()
                SectionHeader("System integration")

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Jarvis Mic",
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = JarvisColors.TextPrimary,
                        )
                        Text(
                            "Always-on listening (foreground service). Uses battery + a persistent notification.",
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            color = JarvisColors.Muted,
                        )
                    }
                    Switch(
                        checked = wakeEnabled,
                        onCheckedChange = { toggleWake(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = JarvisColors.Cyan,
                            checkedTrackColor = JarvisColors.Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = JarvisColors.Muted,
                            uncheckedTrackColor = JarvisColors.Muted.copy(alpha = 0.2f),
                        ),
                    )
                }

                if (wakeEnabled && !overlayGranted) {
                    NeutralButton("Allow \u201Cdisplay over other apps\u201D (needed to open on wake)") { requestOverlay() }
                }
                if (wakeEnabled && !batteryExempt) {
                    NeutralButton("Allow background activity (keep listening always-on)") { requestBattery() }
                }

                if (isDefaultAssistant) {
                    Text(
                        "\u2713 Jarvis is your default assistant",
                        fontFamily = DmSans,
                        fontSize = 13.sp,
                        color = JarvisColors.Cyan,
                    )
                    NeutralButton("Open Default Apps settings") { openAssistantSettings(context) }
                } else {
                    Text(
                        "Set Jarvis as your device\u2019s digital assistant so it can wake from the background.",
                        fontFamily = DmSans,
                        fontSize = 13.sp,
                        color = JarvisColors.Muted,
                    )
                    // Primary: try the ROLE_ASSISTANT system dialog (works on stock Android).
                    // If the dialog is suppressed (OEM restriction), the result callback opens
                    // Default Apps settings automatically.
                    NeutralButton("Set as default assistant") {
                        Log.i(SETTINGS_TAG, "Set as default assistant button tapped")
                        requestDefaultAssistant()
                    }
                    Text(
                        "If nothing opens: go to Settings \u2192 Apps \u2192 Default apps \u2192 Digital assistant app \u2192 select Jarvis.",
                        fontFamily = DmSans,
                        fontSize = 11.sp,
                        color = JarvisColors.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = JarvisColors.CyanText,
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = JarvisColors.Cyan.copy(alpha = 0.08f),
    )
}

@Composable
private fun NeutralButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisColors.CyanBorder),
    ) {
        Text(
            text = text,
            fontFamily = DmSans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = JarvisColors.TextPrimary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/**
 * Open the Android settings screen where the user can change the default digital assistant.
 *
 * Tried in order of directness:
 * 1. ACTION_MANAGE_DEFAULT_APPS_SETTINGS — opens "Default apps"; user taps "Digital assistant app"
 * 2. ACTION_VOICE_INPUT_SETTINGS (API 31+) — may show assistant section on some stock builds
 * 3. ACTION_SETTINGS — generic fallback (always succeeds)
 *
 * All use FLAG_ACTIVITY_NEW_TASK so they launch cleanly from a service/composable context.
 */
private fun openAssistantSettings(context: Context) {
    Log.i(SETTINGS_TAG, "openAssistantSettings: opening Default Apps settings")
    val candidates = buildList {
        // Most reliable path to "Digital assistant app" on all Android 4.4+ devices.
        add(Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        // On Android 12+ this sometimes leads to a voice assistant section directly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS))
        }
        // Last resort — always available.
        add(Intent(AndroidSettings.ACTION_SETTINGS))
    }.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    for (intent in candidates) {
        val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        if (ok) {
            Log.i(SETTINGS_TAG, "openAssistantSettings: launched ${intent.action}")
            return
        }
        Log.w(SETTINGS_TAG, "openAssistantSettings: failed to launch ${intent.action}")
    }
    Log.e(SETTINGS_TAG, "openAssistantSettings: all candidates failed")
}

private fun voiceLabel(voiceId: String, voices: List<ElevenVoice>): String {
    if (voiceId.isBlank()) return ""
    return voices.firstOrNull { it.voice_id == voiceId }?.name ?: voiceId
}
