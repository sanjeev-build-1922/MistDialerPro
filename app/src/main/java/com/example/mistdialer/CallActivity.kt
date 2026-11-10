package com.example.mistdialer

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import java.util.Locale

class CallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enable120HzHighRefreshRate()
        configureLockScreenFlags()
        handleIncomingIntent(intent)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = NothingTokens.BgFoundation,
                    surface = NothingTokens.GlassBBg,
                    primary = NothingTokens.NothingRed
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = NothingTokens.BgFoundation
                ) {
                    val callState by CallStateController.uiState.collectAsState()

                    LaunchedEffect(callState.stage) {
                        if (callState.stage == UiCallStage.ENDED) {
                            delay(320)
                            finishCallScreenCleanly()
                        }
                    }

                    InCallRootScreen(
                        state = callState,
                        onAnswer = { CallStateController.answerCall(openCallScreen = false) },
                        onEndOrDecline = {
                            CallStateController.endOrDeclineCall()
                            if (!CallStateController.hasActiveTelecomCall()) {
                                finishCallScreenCleanly()
                            }
                        },
                        onAnswerWaiting = { CallStateController.answerWaitingCall() },
                        onDeclineWaiting = { CallStateController.declineWaitingCall() },
                        onSwapHeldCall = { CallStateController.swapActiveAndHeldCalls() },
                        onToggleMute = { CallStateController.toggleMute() },
                        onToggleSpeaker = { CallStateController.toggleSpeaker() },
                        onToggleHold = { CallStateController.toggleHold() },
                        onSendDtmf = { digit -> CallStateController.sendDtmf(digit) },
                        onAddCall = {
                            val addCallIntent = Intent(this@CallActivity, MainActivity::class.java).apply {
                                putExtra(MainActivity.EXTRA_OPEN_DIALPAD, true)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            startActivity(addCallIntent)
                        },
                        onSwitchStage = { stage ->
                            CallStateController.switchSimulatedStage(stage, false)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val passedNumber = intent?.getStringExtra(EXTRA_PHONE_NUMBER)
        val passedStage = intent?.getStringExtra(EXTRA_INITIAL_STAGE)
        if (!passedNumber.isNullOrBlank() && !CallStateController.hasActiveTelecomCall()) {
            val stage = when (passedStage) {
                "INCOMING" -> UiCallStage.INCOMING
                "ACTIVE" -> UiCallStage.ACTIVE
                else -> UiCallStage.OUTGOING
            }
            CallStateController.startSimulatedCall(
                number = passedNumber,
                stage = stage,
                isUnknown = passedStage == "UNKNOWN"
            )
        }
    }

    private fun finishCallScreenCleanly() {
        try {
            finishAndRemoveTask()
        } catch (_: Throwable) {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        CallStateController.setCallScreenVisibility(true)
    }

    override fun onPause() {
        CallStateController.setCallScreenVisibility(false)
        super.onPause()
    }

    private fun configureLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
    }

    private fun enable120HzHighRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val display = display
                if (display != null) {
                    val supportedModes = display.supportedModes
                    val maxMode = supportedModes.maxByOrNull { it.refreshRate }
                    if (maxMode != null && maxMode.refreshRate >= 90f) {
                        window.attributes = window.attributes.apply {
                            preferredDisplayModeId = maxMode.modeId
                        }
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val modes = window.windowManager.defaultDisplay.supportedModes
                val maxMode = modes.maxByOrNull { it.refreshRate }
                if (maxMode != null && maxMode.refreshRate >= 90f) {
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = maxMode.modeId
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_INITIAL_STAGE = "extra_initial_stage"
    }
}

@Composable
fun InCallRootScreen(
    state: ActiveCallUiState,
    onAnswer: () -> Unit,
    onEndOrDecline: () -> Unit,
    onAnswerWaiting: () -> Unit = {},
    onDeclineWaiting: () -> Unit = {},
    onSwapHeldCall: () -> Unit = {},
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleHold: () -> Unit,
    onSendDtmf: (Char) -> Unit,
    onAddCall: () -> Unit = {},
    onSwitchStage: (UiCallStage) -> Unit
) {
    DotMatrixBackground(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        AnimatedContent(
            targetState = state.stage,
            transitionSpec = {
                fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(150))
            },
            label = "incall_stage_transition"
        ) { targetStage ->
            when (targetStage) {
                UiCallStage.INCOMING -> Screen05IncomingCall(
                    state = state,
                    onAnswer = onAnswer,
                    onDecline = onEndOrDecline
                )

                UiCallStage.OUTGOING -> Screen06OutgoingCall(
                    state = state,
                    onEndCall = onEndOrDecline,
                    onSwitchToActive = { onSwitchStage(UiCallStage.ACTIVE) }
                )

                UiCallStage.ACTIVE, UiCallStage.ENDED -> Screen07ActiveCall(
                    state = state,
                    onAnswerWaiting = onAnswerWaiting,
                    onDeclineWaiting = onDeclineWaiting,
                    onSwapHeldCall = onSwapHeldCall,
                    onToggleMute = onToggleMute,
                    onToggleSpeaker = onToggleSpeaker,
                    onToggleHold = onToggleHold,
                    onSendDtmf = onSendDtmf,
                    onAddCall = onAddCall,
                    onEndCall = onEndOrDecline
                )
            }
        }
    }
}

/**
 * SCREEN 05 — PREMIUM INCOMING CALL SCREEN (120FPS Hardware Accelerated)
 * - Directional Background Dot-Matrix Zoom Wave
 * - Press & Stretch Upward Gesture on the White Call Orb
 * - Google Phone style Quick-Reply SMS Sheet when tapping the Message button
 */
@Composable
fun Screen05IncomingCall(
    state: ActiveCallUiState,
    onAnswer: () -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val infiniteTransition = rememberInfiniteTransition(label = "incoming_wave_transition")

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dot_wave_phase"
    )

    val ringPulse by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ring_pulse"
    )

    val maxStretchPx = remember(density) { with(density) { 125.dp.toPx() } }
    val triggerThresholdPx = remember(density) { with(density) { 84.dp.toPx() } }

    var rawDragY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var hasTriggeredAnswer by remember { mutableStateOf(false) }
    var showStretchHint by remember { mutableStateOf(false) }
    var showQuickReplySheet by remember { mutableStateOf(false) }

    LaunchedEffect(showStretchHint) {
        if (showStretchHint) {
            delay(1600)
            showStretchHint = false
        }
    }

    val animatedDragY by animateFloatAsState(
        targetValue = if (isDragging) rawDragY else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "stretch_spring"
    )

    val stretchProgress = (-animatedDragY / triggerThresholdPx).coerceIn(0f, 1.15f)
    val showSeparateNumber = remember(state.displayName, state.number) {
        state.displayName.replace(" ", "") != state.number.replace(" ", "")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        DirectionalStretchDotWaveCanvas(
            wavePhase = wavePhase,
            stretchProgress = stretchProgress.coerceIn(0f, 1f)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 20.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SoftGlassBox {
                MonoNumText(
                    text = "HD VOICE · ${state.roleTag.uppercase()}",
                    fontSize = 10.sp,
                    color = NothingTokens.TextSecondary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircleMonochromeAvatar(
                    initials = state.displayName.take(2),
                    size = 96.dp,
                    hasAccentBorder = true
                )

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = state.displayName,
                    color = Color.White,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center
                )

                if (showSeparateNumber) {
                    Spacer(modifier = Modifier.height(6.dp))
                    MonoNumText(
                        text = state.number,
                        fontSize = 14.sp,
                        color = NothingTokens.TextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .clip(MistShapes.Pill)
                        .background(NothingTokens.NothingRed)
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "is calling...",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MonoNumText(
                    text = when {
                        stretchProgress >= 0.85f -> "RELEASE TO ANSWER ↑"
                        showStretchHint -> "HOLD & STRETCH UP TO ANSWER ↑"
                        else -> "STRETCH UP TO ANSWER ↑"
                    },
                    fontSize = 10.sp,
                    color = if (stretchProgress > 0.2f || showStretchHint) Color.White else NothingTokens.TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Red Decline Button
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(NothingTokens.NothingRed)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDecline()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        BareCallIcon(
                            tint = Color.White,
                            size = 24.dp,
                            modifier = Modifier.rotate(135f)
                        )
                    }

                    // Center Stretch-to-Answer Capsule & Orb (RenderThread graphicsLayer accelerated)
                    Box(
                        modifier = Modifier.size(width = 136.dp, height = 140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cx = size.width / 2f
                            val anchorCy = size.height / 2f
                            val currentCy = (anchorCy + animatedDragY).coerceAtMost(anchorCy)
                            val capsuleRadius = 38.dp.toPx()

                            val topEdge = currentCy - capsuleRadius
                            val bottomEdge = anchorCy + capsuleRadius
                            val totalHeight = (bottomEdge - topEdge).coerceAtLeast(capsuleRadius * 2f)
                            val clampedProgress = stretchProgress.coerceIn(0f, 1f)

                            drawCircle(
                                color = Color.White.copy(alpha = 0.05f + 0.08f * clampedProgress),
                                radius = 58.dp.toPx() * ringPulse,
                                center = Offset(cx, anchorCy)
                            )

                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.12f + 0.18f * clampedProgress),
                                topLeft = Offset(cx - capsuleRadius, topEdge),
                                size = Size(capsuleRadius * 2f, totalHeight),
                                cornerRadius = CornerRadius(capsuleRadius, capsuleRadius)
                            )
                            drawRoundRect(
                                color = if (stretchProgress >= 0.9f) NothingTokens.EmeraldActive else Color.White.copy(alpha = 0.35f),
                                topLeft = Offset(cx - capsuleRadius, topEdge),
                                size = Size(capsuleRadius * 2f, totalHeight),
                                cornerRadius = CornerRadius(capsuleRadius, capsuleRadius),
                                style = Stroke(width = 1.4.dp.toPx())
                            )
                        }

                        val orbScale = 1f + (0.08f * stretchProgress.coerceIn(0f, 1f))
                        Box(
                            modifier = Modifier
                                .graphicsLayer {
                                    translationY = animatedDragY
                                    scaleX = orbScale
                                    scaleY = orbScale
                                }
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(
                                    if (stretchProgress >= 0.95f) NothingTokens.EmeraldActive else Color.White
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showStretchHint = true
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = {
                                            isDragging = true
                                            hasTriggeredAnswer = false
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            rawDragY = (rawDragY + dragAmount.y).coerceIn(-maxStretchPx, 0f)
                                            if (-rawDragY >= triggerThresholdPx && !hasTriggeredAnswer) {
                                                hasTriggeredAnswer = true
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                        },
                                        onDragEnd = {
                                            val reachedTarget = -rawDragY >= triggerThresholdPx
                                            isDragging = false
                                            rawDragY = 0f
                                            if (reachedTarget) {
                                                onAnswer()
                                            } else {
                                                showStretchHint = true
                                            }
                                        },
                                        onDragCancel = {
                                            isDragging = false
                                            rawDragY = 0f
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            BareCallIcon(tint = Color.Black, size = 26.dp)
                        }
                    }

                    // Right Quick-Reply SMS Button
                    SoftGlassBox(
                        shape = CircleShape,
                        onClick = { showQuickReplySheet = true },
                        modifier = Modifier.size(62.dp)
                    ) {
                        Text(text = "✉", color = Color.White, fontSize = 20.sp)
                    }
                }
            }
        }

        // Google Phone-style Quick-Reply SMS Overlay
        if (showQuickReplySheet) {
            val quickReplies = remember {
                listOf(
                    "Can't talk right now, I'll call you right back.",
                    "I'm in a meeting, please text me.",
                    "On my way! Be there soon.",
                    "Can I call you later?"
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xC8000000))
                    .clickable { showQuickReplySheet = false }
                    .padding(horizontal = 20.dp, vertical = 32.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                FocalGlassBox(
                    shape = MistShapes.Radius32,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = false) {}
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NdotText(text = "DECLINE WITH MESSAGE", fontSize = 13.sp)
                            SoftGlassBox(
                                shape = CircleShape,
                                onClick = { showQuickReplySheet = false },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Text(text = "✕", color = Color.White, fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        quickReplies.forEach { replyText ->
                            SoftGlassBox(
                                shape = MistShapes.Radius18,
                                onClick = {
                                    showQuickReplySheet = false
                                    onDecline()
                                    DialerRepository.openSmsComposer(context, state.number, replyText)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = replyText,
                                    color = NothingTokens.TextPrimary,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                )
                            }
                        }

                        SoftGlassBox(
                            shape = MistShapes.Radius18,
                            onClick = {
                                showQuickReplySheet = false
                                onDecline()
                                DialerRepository.openSmsComposer(context, state.number)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "✎ Write custom message...",
                                color = NothingTokens.NothingRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * SCREEN 06 — CLEAN OUTGOING CALL SCREEN (120FPS RenderThread Pulse)
 */
@Composable
fun Screen06OutgoingCall(
    state: ActiveCallUiState,
    onEndCall: () -> Unit,
    onSwitchToActive: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "outgoing_rings")
    val outerPulse by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "outer_ring_pulse"
    )

    val showSeparateNumber = remember(state.displayName, state.number) {
        state.displayName.replace(" ", "") != state.number.replace(" ", "")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SoftGlassBox {
            MonoNumText(
                text = "CALLING · ${state.roleTag.uppercase()}",
                fontSize = 10.sp,
                color = NothingTokens.TextSecondary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircleMonochromeAvatar(
                initials = state.displayName.take(2),
                size = 96.dp,
                hasAccentBorder = true
            )

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = state.displayName,
                color = Color.White,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center
            )

            if (showSeparateNumber) {
                Spacer(modifier = Modifier.height(6.dp))
                MonoNumText(
                    text = state.number,
                    fontSize = 14.sp,
                    color = NothingTokens.TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .clip(MistShapes.Pill)
                    .background(NothingTokens.NothingRed)
                    .clickable { onSwitchToActive() }
                    .padding(horizontal = 22.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "is calling...",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Box(
            modifier = Modifier
                .size(210.dp)
                .graphicsLayer {
                    scaleX = outerPulse
                    scaleY = outerPulse
                }
                .clip(CircleShape)
                .background(Color(0x06FFFFFF))
                .border(1.dp, Color(0x12FFFFFF), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(154.dp)
                    .clip(CircleShape)
                    .background(Color(0x09FFFFFF))
                    .border(1.dp, Color(0x16FFFFFF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(106.dp)
                        .clip(CircleShape)
                        .background(Color(0x0EFFFFFF))
                        .border(1.dp, Color(0x1FFFFFFF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(NothingTokens.NothingRed)
                            .clickable { onEndCall() },
                        contentAlignment = Alignment.Center
                    ) {
                        BareCallIcon(
                            tint = Color.White,
                            size = 26.dp,
                            modifier = Modifier.rotate(135f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * SCREEN 07 — PREMIUM ACTIVE CONNECTED CALL SCREEN
 * - Includes Call Waiting Banner (when a 2nd person calls during an active call)
 * - Includes Held Call Swap Bar (when 2 calls are connected)
 * - Tall In-Call Keypad Card with Tap-Outside-to-Dismiss
 * - Smooth Call Ended status feedback when disconnecting
 */
@Composable
fun Screen07ActiveCall(
    state: ActiveCallUiState,
    onAnswerWaiting: () -> Unit = {},
    onDeclineWaiting: () -> Unit = {},
    onSwapHeldCall: () -> Unit = {},
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleHold: () -> Unit,
    onSendDtmf: (Char) -> Unit,
    onAddCall: () -> Unit = {},
    onEndCall: () -> Unit
) {
    var isKeypadOpen by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    val toneGenerator = remember {
        try {
            ToneGenerator(AudioManager.STREAM_DTMF, 70)
        } catch (_: Exception) {
            null
        }
    }
    DisposableEffect(Unit) {
        onDispose { toneGenerator?.release() }
    }

    LaunchedEffect(state.isOnHold, state.connectTimeMillis, state.stage) {
        while (state.stage == UiCallStage.ACTIVE) {
            if (!state.isOnHold) {
                val diff = ((System.currentTimeMillis() - state.connectTimeMillis) / 1000L).coerceAtLeast(0L)
                elapsedSeconds = diff
            }
            delay(1000)
        }
    }

    val durationText = remember(elapsedSeconds) {
        val mins = elapsedSeconds / 60
        val secs = elapsedSeconds % 60
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    val isCallEnding = state.stage == UiCallStage.ENDED

    val outsideTapModifier = if (isKeypadOpen) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) {
            isKeypadOpen = false
        }
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(outsideTapModifier)
            .padding(horizontal = 22.dp)
            .padding(top = 14.dp, bottom = 38.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header + Call Waiting / Held Call Banner
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (state.hasWaitingCall) {
                FocalGlassBox(
                    shape = MistShapes.Radius24,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                MonoNumText(
                                    text = "INCOMING CALL WAITING",
                                    fontSize = 10.sp,
                                    color = NothingTokens.NothingRed,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                NdotText(
                                    text = state.waitingCallerName.ifBlank { state.waitingCallerNumber },
                                    fontSize = 15.sp
                                )
                                if (state.waitingCallerNumber.isNotBlank()) {
                                    MonoNumText(text = state.waitingCallerNumber, fontSize = 11.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(MistShapes.Pill)
                                    .background(NothingTokens.NothingRed)
                                    .clickable { onDeclineWaiting() }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Decline",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(MistShapes.Pill)
                                    .background(NothingTokens.EmeraldActive)
                                    .clickable { onAnswerWaiting() }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Hold & Accept",
                                    color = Color.Black,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            } else if (state.hasSecondaryHeldCall) {
                SoftGlassBox(
                    onClick = onSwapHeldCall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ON HOLD · ${state.secondaryHeldCallerName}",
                            color = NothingTokens.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "SWAP ⇄",
                            color = NothingTokens.EmeraldActive,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                SoftGlassBox {
                    MonoNumText(
                        text = if (isCallEnding) "CALL ENDED" else "HD VOICE · CONNECTED",
                        fontSize = 10.sp,
                        color = if (isCallEnding) NothingTokens.NothingRed else NothingTokens.TextSecondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            CircleMonochromeAvatar(
                initials = state.displayName.take(2),
                size = if (isKeypadOpen || state.hasWaitingCall) 60.dp else 76.dp,
                hasAccentBorder = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            NdotText(
                text = state.displayName,
                fontSize = if (isKeypadOpen || state.hasWaitingCall) 19.sp else 22.sp,
                letterSpacing = 1.8.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .clip(MistShapes.Pill)
                    .background(Color(0xFF141418))
                    .border(1.dp, Color(0x22FFFFFF), MistShapes.Pill)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isCallEnding -> NothingTokens.NothingRed
                                state.isOnHold -> NothingTokens.AmberHold
                                else -> NothingTokens.EmeraldActive
                            }
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                MonoNumText(
                    text = when {
                        isCallEnding -> "ENDED · $durationText"
                        state.isOnHold -> "ON HOLD · $durationText"
                        else -> durationText
                    },
                    fontSize = 12.sp,
                    color = NothingTokens.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Middle Section: Tall Premium In-Call DTMF Keypad Card OR 3x2 B&W Vector Control Grid
        if (isKeypadOpen) {
            FocalGlassBox(
                shape = RoundedCornerShape(34.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NdotText(
                            text = state.dtmfHistory.ifEmpty { "KEYPAD" },
                            fontSize = 20.sp,
                            letterSpacing = 2.sp
                        )
                        SoftGlassBox(
                            shape = MistShapes.Pill,
                            onClick = { isKeypadOpen = false }
                        ) {
                            Text(
                                text = "Hide ✕",
                                color = NothingTokens.TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    val dtmfRows = remember {
                        listOf(
                            listOf('1' to "مه", '2' to "ABC", '3' to "DEF"),
                            listOf('4' to "GHI", '5' to "JKL", '6' to "MNO"),
                            listOf('7' to "PQRS", '8' to "TUV", '9' to "WXYZ"),
                            listOf('*' to "", '0' to "+", '#' to "")
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        dtmfRows.forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                rowItems.forEach { (digitChar, subLabel) ->
                                    SoftGlassBox(
                                        shape = CircleShape,
                                        onClick = {
                                            toneGenerator?.startTone(ToneGenerator.TONE_DTMF_1, 80)
                                            onSendDtmf(digitChar)
                                        },
                                        modifier = Modifier.size(62.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            NdotText(
                                                text = digitChar.toString(),
                                                fontSize = 22.sp,
                                                color = Color.White,
                                                letterSpacing = 0.sp
                                            )
                                            if (subLabel.isNotEmpty()) {
                                                MonoNumText(
                                                    text = subLabel,
                                                    fontSize = 8.sp,
                                                    color = NothingTokens.TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ActiveCallVectorButton(
                        label = "Mute",
                        isActive = state.isMuted,
                        onClick = onToggleMute
                    ) { iconTint ->
                        VectorMuteIcon(tint = iconTint, isMuted = state.isMuted, size = 22.dp)
                    }

                    ActiveCallVectorButton(
                        label = "Keypad",
                        isActive = isKeypadOpen,
                        onClick = { isKeypadOpen = true }
                    ) { iconTint ->
                        VectorKeypadIcon(tint = iconTint, size = 22.dp)
                    }

                    ActiveCallVectorButton(
                        label = if (state.isBluetoothOn) "Bluetooth" else "Speaker",
                        isActive = state.isSpeakerOn || state.isBluetoothOn,
                        onClick = onToggleSpeaker
                    ) { iconTint ->
                        VectorSpeakerIcon(tint = iconTint, size = 22.dp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ActiveCallVectorButton(
                        label = if (state.hasSecondaryHeldCall) "Swap" else "Add Call",
                        isActive = state.hasSecondaryHeldCall,
                        onClick = {
                            if (state.hasSecondaryHeldCall) onSwapHeldCall() else onAddCall()
                        }
                    ) { iconTint ->
                        VectorAddCallIcon(tint = iconTint, size = 22.dp)
                    }

                    ActiveCallVectorButton(
                        label = "Hold",
                        isActive = state.isOnHold,
                        onClick = onToggleHold
                    ) { iconTint ->
                        VectorHoldIcon(tint = iconTint, size = 20.dp)
                    }

                    ActiveCallVectorButton(
                        label = if (isRecording) "Recording" else "Record",
                        isActive = isRecording,
                        onClick = { isRecording = !isRecording }
                    ) { iconTint ->
                        VectorRecordIcon(tint = iconTint, isRecording = isRecording, size = 22.dp)
                    }
                }
            }
        }

        // Raised Bottom Nothing Red End Call Button
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(NothingTokens.NothingRed)
                    .clickable { onEndCall() },
                contentAlignment = Alignment.Center
            ) {
                BareCallIcon(
                    tint = Color.White,
                    size = 28.dp,
                    modifier = Modifier.rotate(135f)
                )
            }
        }
    }
}

@Composable
private fun ActiveCallVectorButton(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    iconContent: @Composable (Color) -> Unit
) {
    val iconTint = if (isActive) Color.Black else Color.White
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SoftGlassBox(
            shape = CircleShape,
            isSelected = isActive,
            onClick = onClick,
            modifier = Modifier.size(72.dp)
        ) {
            iconContent(iconTint)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = if (isActive) Color.White else NothingTokens.TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
