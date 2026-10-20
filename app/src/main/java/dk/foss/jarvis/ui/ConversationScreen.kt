package dk.foss.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.PI
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// ConversationScreen — stateful Compose entry point (logic unchanged)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ConversationScreen(vm: ConversationViewModel, assistTrigger: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state
    val transcript by vm.transcript
    val reply by vm.reply
    val error by vm.error
    val hint by vm.hint
    val working by vm.working
    val stalled by vm.stalled

    val segments = vm.segments
    val speakingIndex by vm.speakingIndex
    val pendingText by vm.pendingText
    val listState = rememberLazyListState()

    // ── permissions ───────────────────────────────────────────────────────────

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) vm.startListening()
    }

    // Lazy READ_CONTACTS request
    var contactsNonce by remember { mutableStateOf(0) }
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.onContactsPermissionResult(granted, contactsNonce) }
    val contactsPermissionRequest by vm.contactsPermissionRequest
    LaunchedEffect(contactsPermissionRequest) {
        if (contactsPermissionRequest > 0) {
            contactsNonce = contactsPermissionRequest
            contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    // Lazy CALL_PHONE request
    var callPhoneNonce by remember { mutableStateOf(0) }
    val callPhoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.onCallPhonePermissionResult(granted, callPhoneNonce) }
    val callPhonePermissionRequest by vm.callPhonePermissionRequest
    LaunchedEffect(callPhonePermissionRequest) {
        if (callPhonePermissionRequest > 0) {
            callPhoneNonce = callPhonePermissionRequest
            callPhoneLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    LaunchedEffect(Unit) {
        vm.resetView()
        if (!hasPermission) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        else vm.startListening()
    }

    var lastTrigger by remember { mutableStateOf(assistTrigger) }
    LaunchedEffect(assistTrigger) {
        if (assistTrigger != lastTrigger) {
            lastTrigger = assistTrigger
            if (hasPermission && state == ConvState.Idle) vm.startListening(fromWake = true)
        }
    }

    val exitRequested by vm.exitRequested
    LaunchedEffect(exitRequested) {
        if (exitRequested) { vm.consumeExitRequest(); onExit() }
    }

    LaunchedEffect(speakingIndex) {
        if (speakingIndex in 0 until segments.size) listState.animateScrollToItem(speakingIndex)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.stopAll()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); vm.stopAll() }
    }

    // ── visual shell ──────────────────────────────────────────────────────────

    val onMicTap = {
        if (hasPermission) vm.onMicTap()
        else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    DeepSpaceBackground(active = state != ConvState.Idle && error == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Close button (top-right)
            IconButton(
                onClick = { vm.stopAll(); onExit() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close conversation",
                    tint = JarvisColors.Muted,
                )
            }

            if (error != null) {
                // Error state — centred, full overlay
                ErrorOverlay(
                    errorMessage = error!!,
                    onRetry = {
                        if (hasPermission) vm.onMicTap()
                        else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onDismiss = onExit,
                )
            } else {
                // Normal states: orb + context area + mic control
                VoiceInterface(
                    state = state,
                    transcript = transcript,
                    reply = reply,
                    hint = hint,
                    working = working,
                    stalled = stalled,
                    segments = segments,
                    speakingIndex = speakingIndex,
                    pendingText = pendingText,
                    listState = listState,
                    onMicTap = onMicTap,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VoiceInterface — the main voice UI body
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VoiceInterface(
    state: ConvState,
    transcript: String,
    reply: String,
    hint: String?,
    working: Boolean,
    stalled: Boolean,
    segments: List<String>,
    speakingIndex: Int,
    pendingText: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onMicTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Top: state label
        AnimatedStateLabel(state = state, stalled = stalled)

        // Middle: central orb + context text
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.weight(1f),
        ) {
            Spacer(Modifier.height(8.dp))

            // Central reactive orb
            StateOrb(state = state, working = working)

            // Context area: transcript, reply, segments, hints
            ContextArea(
                state = state,
                transcript = transcript,
                reply = reply,
                hint = hint,
                segments = segments,
                speakingIndex = speakingIndex,
                pendingText = pendingText,
                listState = listState,
            )
        }

        // Bottom: mic / stop control
        MicControl(state = state, onTap = onMicTap)
        Spacer(Modifier.height(8.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// State label
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AnimatedStateLabel(state: ConvState, stalled: Boolean) {
    val (label, color) = when (state) {
        ConvState.Idle      -> "WAKE WORD ACTIVE" to JarvisColors.Muted
        ConvState.Listening -> "LISTENING" to JarvisColors.Cyan
        ConvState.Thinking  -> (if (stalled) "WORKING…" else "THINKING") to JarvisColors.ThinkBlue
        ConvState.Speaking  -> "SPEAKING" to JarvisColors.Blue
    }
    val textColor by animateColorAsState(
        targetValue = if (state == ConvState.Thinking && stalled) JarvisColors.Muted else color,
        animationSpec = tween(400),
        label = "stateColor",
    )
    StatusTag(label, textColor)
}

// ─────────────────────────────────────────────────────────────────────────────
// Central orb — animates differently per state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StateOrb(state: ConvState, working: Boolean) {
    val transition = rememberInfiniteTransition(label = "orb")

    val orbSize: Dp = when (state) {
        ConvState.Idle     -> 96.dp
        ConvState.Listening -> 112.dp
        ConvState.Thinking  -> 104.dp
        ConvState.Speaking  -> 120.dp
    }

    // Pulse speed differs by state: slow idle, rapid listening, medium thinking/speaking
    val pulseDurationMs = when (state) {
        ConvState.Idle     -> 3200
        ConvState.Listening -> 900
        ConvState.Thinking  -> 1400
        ConvState.Speaking  -> 800
    }

    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue  = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    // Inner glow rotation for listening / speaking
    val glowRotate by transition.animateFloat(
        initialValue = 0f,
        targetValue  = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "glowRot",
    )

    val coreColor: Color = when (state) {
        ConvState.Idle      -> JarvisColors.Blue.copy(alpha = 0.7f)
        ConvState.Listening -> JarvisColors.Cyan.copy(alpha = 0.85f)
        ConvState.Thinking  -> JarvisColors.ThinkBlue.copy(alpha = 0.75f)
        ConvState.Speaking  -> JarvisColors.Blue.copy(alpha = 0.9f)
    }
    val glowColor: Color = when (state) {
        ConvState.Idle      -> JarvisColors.Blue.copy(alpha = 0.15f)
        ConvState.Listening -> JarvisColors.Cyan.copy(alpha = 0.25f)
        ConvState.Thinking  -> JarvisColors.ThinkBlue.copy(alpha = 0.20f)
        ConvState.Speaking  -> JarvisColors.Blue.copy(alpha = 0.30f)
    }

    val animOrbSize by animateFloatAsState(
        targetValue = orbSize.value,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "orbSize",
    )

    Box(contentAlignment = Alignment.Center) {
        // Outer ring pulse
        Box(
            modifier = Modifier
                .size(animOrbSize.dp)
                .scale(pulseScale)
                .alpha(pulseAlpha)
                .border(1.5.dp, glowColor.copy(alpha = 0.6f), CircleShape),
        )

        // Rotating gradient glow ring (listening + speaking)
        if (state == ConvState.Listening || state == ConvState.Speaking) {
            Box(
                modifier = Modifier
                    .size((animOrbSize * 1.1f).dp)
                    .drawBehind {
                        val r = size.minDimension / 2
                        val cx = size.width / 2
                        val cy = size.height / 2
                        val radAngle = glowRotate * PI.toFloat() / 180f
                        drawCircle(
                            brush = Brush.sweepGradient(
                                0f to Color.Transparent,
                                0.5f to glowColor,
                                1f to Color.Transparent,
                                center = Offset(cx, cy),
                            ),
                            radius = r,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                        )
                    },
            )
        }

        // Core orb
        Box(
            modifier = Modifier
                .size(animOrbSize.dp)
                .drawBehind {
                    // Soft glow halo behind the orb
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glowColor.copy(alpha = 0.6f), Color.Transparent),
                            radius = size.minDimension * 0.8f,
                        ),
                    )
                    // Core fill
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                coreColor.copy(alpha = 0.95f),
                                coreColor.copy(alpha = 0.5f),
                                Color.Transparent,
                            ),
                        ),
                    )
                },
        ) {
            // Inner icon: mic for idle/listening, nothing for thinking/speaking
            if (state == ConvState.Idle || state == ConvState.Listening) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier
                        .size((animOrbSize * 0.4f).dp)
                        .align(Alignment.Center),
                    tint = Color.White.copy(alpha = 0.9f),
                )
            }
            // Thinking: three pulsing dots inside the orb
            if (state == ConvState.Thinking) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(3) { i ->
                        val dotPulse by transition.animateFloat(
                            initialValue = 0.2f,
                            targetValue  = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(500, delayMillis = i * 170, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse,
                            ),
                            label = "dot$i",
                        )
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .alpha(dotPulse)
                                .background(Color.White.copy(alpha = 0.85f), CircleShape),
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Context area
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ContextArea(
    state: ConvState,
    transcript: String,
    reply: String,
    hint: String?,
    segments: List<String>,
    speakingIndex: Int,
    pendingText: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
        label = "contextAnim",
    ) { s ->
        when (s) {
            ConvState.Idle -> IdleHint(hint)

            ConvState.Listening -> if (transcript.isNotEmpty()) {
                TranscriptCard(transcript)
            } else {
                IdleHint("Listening…")
            }

            ConvState.Thinking -> {
                val displayText = if (transcript.isNotEmpty()) transcript else reply
                if (displayText.isNotEmpty()) TranscriptCard(displayText) else Box(Modifier.height(80.dp))
            }

            ConvState.Speaking -> SpeakingCards(
                segments = segments,
                speakingIndex = speakingIndex,
                pendingText = pendingText,
                listState = listState,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-content composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IdleHint(text: String?) {
    if (text == null) {
        Text(
            text = "Tap the orb or say \"Jarvis\"",
            fontFamily = DmSans,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            color = JarvisColors.Muted,
            textAlign = TextAlign.Center,
        )
    } else {
        Text(
            text = text,
            fontFamily = DmSans,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            color = JarvisColors.CyanText,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TranscriptCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(JarvisColors.GlassBg)
            .border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "\u201C$text\u201D",
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Normal,
            fontSize = 17.sp,
            color = JarvisColors.TextPrimary.copy(alpha = 0.88f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SpeakingCards(
    segments: List<String>,
    speakingIndex: Int,
    pendingText: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(JarvisColors.GlassBg)
            .border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(16.dp)),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            items(segments.size) { i ->
                val isSpeaking = i == speakingIndex
                Text(
                    text = segments[i],
                    fontFamily = SpaceGrotesk,
                    fontWeight = if (isSpeaking) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = if (isSpeaking) 18.sp else 15.sp,
                    textAlign = TextAlign.Center,
                    color = if (isSpeaking) JarvisColors.Cyan else JarvisColors.TextPrimary.copy(alpha = 0.75f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (pendingText.isNotEmpty()) {
                item {
                    Text(
                        text = pendingText,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Light,
                        fontSize = 14.sp,
                        color = JarvisColors.Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mic control button
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MicControl(state: ConvState, onTap: () -> Unit) {
    val isActive = state == ConvState.Listening || state == ConvState.Speaking
    val buttonColor = if (isActive) JarvisColors.Cyan else JarvisColors.Blue
    val icon = if (state == ConvState.Listening) Icons.Default.Stop else Icons.Default.Mic
    val label = when (state) {
        ConvState.Idle      -> "Tap to speak"
        ConvState.Listening -> "Tap to stop"
        ConvState.Thinking  -> "Thinking…"
        ConvState.Speaking  -> "Tap to interrupt"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                buttonColor.copy(alpha = 0.35f),
                                Color.Transparent,
                            ),
                        ),
                    )
                }
                .clip(CircleShape)
                .background(buttonColor.copy(alpha = 0.2f))
                .border(1.5.dp, buttonColor.copy(alpha = 0.6f), CircleShape)
                .clickable(enabled = state != ConvState.Thinking, onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = if (state == ConvState.Thinking) JarvisColors.Muted else buttonColor,
            )
        }
        Text(
            text = label,
            fontFamily = DmSans,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            color = JarvisColors.Muted,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Error overlay
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorOverlay(
    errorMessage: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(32.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(JarvisColors.GlassBg)
                .border(1.dp, JarvisColors.ErrorOrange.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                .padding(24.dp),
        ) {
            Text(
                "\u2300",
                fontSize = 36.sp,
                color = JarvisColors.ErrorOrange,
            )
            Text(
                "Can't reach Jarvis",
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = JarvisColors.ErrorOrange,
            )
            Text(
                errorMessage,
                fontFamily = DmSans,
                fontSize = 13.sp,
                color = JarvisColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(text = "Retry", onClick = onRetry, accent = true)
                PillButton(text = "Dismiss", onClick = onDismiss, accent = false)
            }
        }
    }
}
