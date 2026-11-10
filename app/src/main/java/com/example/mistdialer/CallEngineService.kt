package com.example.mistdialer

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale

enum class UiCallStage {
    INCOMING,
    OUTGOING,
    ACTIVE,
    ENDED
}

@Immutable
data class ActiveCallUiState(
    val number: String = "+44 7700 900412",
    val displayName: String = "MLADEN HOYSS",
    val roleTag: String = "Work",
    val isUnknownCaller: Boolean = false,
    val stage: UiCallStage = UiCallStage.ENDED,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isBluetoothOn: Boolean = false,
    val isOnHold: Boolean = false,
    val connectTimeMillis: Long = System.currentTimeMillis(),
    val dtmfHistory: String = "",
    val wasEverConnected: Boolean = false,
    val initialDirection: CallType = CallType.OUTGOING,
    // Call Waiting & Multi-Call State
    val hasWaitingCall: Boolean = false,
    val waitingCallerName: String = "",
    val waitingCallerNumber: String = "",
    val hasSecondaryHeldCall: Boolean = false,
    val secondaryHeldCallerName: String = ""
)

/**
 * Google-Phone-Grade Call State Controller:
 * - Zero main-thread blocking (contact resolution & ringtone playback run on Dispatchers.IO)
 * - Full Heads-Up Incoming Call Popup (IMPORTANCE_HIGH + CallStyle.forIncomingCall + FullScreenIntent)
 * - Proximity WakeLock management for Earpiece calls
 * - Multi-Call, Call Waiting, Hold/Swap, and Audio Route management
 */
object CallStateController {

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var simulatedFallbackJob: Job? = null

    private val trackedCalls = mutableListOf<Call>()
    private val callDirections = IdentityHashMap<Call, CallType>()
    private val callConnectedMap = IdentityHashMap<Call, Boolean>()
    private val callConnectMillisMap = IdentityHashMap<Call, Long>()

    @Volatile
    private var inCallServiceRef: CallEngineService? = null

    @Volatile
    var isMainActivityVisible: Boolean = false

    @Volatile
    var isCallActivityVisible: Boolean = false

    private val _uiState = MutableStateFlow(ActiveCallUiState())
    val uiState: StateFlow<ActiveCallUiState> = _uiState.asStateFlow()

    private val _callLogUpdateTick = MutableStateFlow(0L)
    val callLogUpdateTick: StateFlow<Long> = _callLogUpdateTick.asStateFlow()

    private val _sessionCallLogs = mutableListOf<CallLogItem>()
    val sessionCallLogs: List<CallLogItem>
        get() = synchronized(_sessionCallLogs) { _sessionCallLogs.toList() }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            onTrackedCallStateChanged(call, state)
        }
    }

    fun hasActiveTelecomCall(): Boolean = synchronized(trackedCalls) { trackedCalls.isNotEmpty() }

    fun attachService(service: CallEngineService) {
        inCallServiceRef = service
    }

    fun detachService() {
        inCallServiceRef = null
    }

    fun setCallScreenVisibility(visible: Boolean) {
        isCallActivityVisible = visible
        val current = _uiState.value
        if (current.stage != UiCallStage.ENDED) {
            inCallServiceRef?.syncCallNotification(current, isScreenVisible = visible)
        } else {
            inCallServiceRef?.cancelCallNotification()
        }
    }

    fun notifyCallLogChanged() {
        _callLogUpdateTick.value = System.currentTimeMillis()
    }

    @Suppress("DEPRECATION")
    internal fun getCallStateSafely(call: Call): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                call.details.state
            } else {
                call.state
            }
        } catch (_: Throwable) {
            Call.STATE_DIALING
        }
    }

    private fun detectCallDirection(call: Call, state: Int): CallType {
        if (state == Call.STATE_RINGING) return CallType.INCOMING
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (call.details?.callDirection == Call.Details.DIRECTION_INCOMING) {
                    return CallType.INCOMING
                }
                if (call.details?.callDirection == Call.Details.DIRECTION_OUTGOING) {
                    return CallType.OUTGOING
                }
            } catch (_: Throwable) {
            }
        }
        return CallType.OUTGOING
    }

    fun onTelecomCallAdded(call: Call) {
        simulatedFallbackJob?.cancel()
        val state = getCallStateSafely(call)
        val dir = detectCallDirection(call, state)

        val rawNum = try {
            call.details?.handle?.schemeSpecificPart ?: ""
        } catch (_: Throwable) {
            ""
        }

        // Auto-reject if number is on the user's Block List
        val appContext = inCallServiceRef?.applicationContext
        if (dir == CallType.INCOMING && appContext != null && DialerRepository.isNumberBlocked(appContext, rawNum)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    call.reject(Call.REJECT_REASON_UNWANTED)
                } else {
                    @Suppress("DEPRECATION")
                    call.reject(false, null)
                }
            } catch (_: Throwable) {
                try {
                    call.disconnect()
                } catch (_: Throwable) {
                }
            }
            return
        }

        synchronized(trackedCalls) {
            if (!trackedCalls.contains(call)) {
                trackedCalls.add(call)
            }
            callDirections[call] = dir
            if (state == Call.STATE_ACTIVE) {
                callConnectedMap[call] = true
                callConnectMillisMap[call] = System.currentTimeMillis()
            } else {
                callConnectedMap.putIfAbsent(call, false)
            }
        }

        try {
            call.registerCallback(callCallback)
        } catch (_: Throwable) {
        }

        rebuildUiStateFromTrackedCalls()
        notifyCallLogChanged()
    }

    private fun onTrackedCallStateChanged(call: Call, state: Int) {
        synchronized(trackedCalls) {
            if (state == Call.STATE_RINGING) {
                callDirections[call] = CallType.INCOMING
            }
            if (state == Call.STATE_ACTIVE) {
                val alreadyConnected = callConnectedMap[call] == true
                callConnectedMap[call] = true
                if (!alreadyConnected) {
                    callConnectMillisMap[call] = System.currentTimeMillis()
                }
            }
        }
        rebuildUiStateFromTrackedCalls()
        notifyCallLogChanged()
    }

    fun onTelecomCallRemoved(call: Call) {
        try {
            call.unregisterCallback(callCallback)
        } catch (_: Throwable) {
        }

        val dir: CallType
        val wasConnected: Boolean
        val connectMs: Long
        val remainingCallsCount: Int

        synchronized(trackedCalls) {
            dir = callDirections.remove(call) ?: detectCallDirection(call, Call.STATE_DISCONNECTED)
            wasConnected = callConnectedMap.remove(call) == true
            connectMs = callConnectMillisMap.remove(call) ?: 0L
            trackedCalls.remove(call)
            remainingCallsCount = trackedCalls.size
        }

        val rawNumber = try {
            call.details?.handle?.schemeSpecificPart ?: ""
        } catch (_: Throwable) {
            ""
        }
        recordCompletedCallToSession(rawNumber, dir, wasConnected, connectMs)

        if (remainingCallsCount == 0) {
            inCallServiceRef?.stopIncomingRinging()
            inCallServiceRef?.playCallEndTone()
            inCallServiceRef?.updateProximityWakeLock(shouldHold = false)
            inCallServiceRef?.cancelCallNotification()
            _uiState.value = _uiState.value.copy(
                stage = UiCallStage.ENDED,
                hasWaitingCall = false,
                hasSecondaryHeldCall = false
            )
        } else {
            rebuildUiStateFromTrackedCalls()
        }
        notifyCallLogChanged()
    }

    private fun rebuildUiStateFromTrackedCalls() {
        val callsSnapshot = synchronized(trackedCalls) { trackedCalls.toList() }
        if (callsSnapshot.isEmpty()) {
            inCallServiceRef?.stopIncomingRinging()
            inCallServiceRef?.updateProximityWakeLock(shouldHold = false)
            inCallServiceRef?.cancelCallNotification()
            _uiState.value = _uiState.value.copy(
                stage = UiCallStage.ENDED,
                hasWaitingCall = false,
                hasSecondaryHeldCall = false
            )
            return
        }

        val activeCalls = callsSnapshot.filter {
            val st = getCallStateSafely(it)
            st != Call.STATE_DISCONNECTED && st != Call.STATE_DISCONNECTING
        }

        if (activeCalls.isEmpty()) {
            inCallServiceRef?.stopIncomingRinging()
            inCallServiceRef?.updateProximityWakeLock(shouldHold = false)
            inCallServiceRef?.cancelCallNotification()
            _uiState.value = _uiState.value.copy(
                stage = UiCallStage.ENDED,
                hasWaitingCall = false,
                hasSecondaryHeldCall = false
            )
            return
        }

        val ringingCall = activeCalls.find { getCallStateSafely(it) == Call.STATE_RINGING }
        val connectedCall = activeCalls.find { getCallStateSafely(it) == Call.STATE_ACTIVE }
        val dialingCall = activeCalls.find {
            val st = getCallStateSafely(it)
            st == Call.STATE_DIALING || st == Call.STATE_CONNECTING || st == Call.STATE_SELECT_PHONE_ACCOUNT
        }
        val holdingCall = activeCalls.find { getCallStateSafely(it) == Call.STATE_HOLDING }

        val primaryCall = connectedCall ?: dialingCall ?: holdingCall ?: ringingCall ?: activeCalls.last()
        val primaryState = getCallStateSafely(primaryCall)

        val waitingCall = if (ringingCall != null && ringingCall != primaryCall) ringingCall else null
        val secondaryHeld = if (holdingCall != null && holdingCall != primaryCall) holdingCall else null

        val primaryNum = try {
            primaryCall.details?.handle?.schemeSpecificPart ?: ""
        } catch (_: Throwable) {
            ""
        }
        val (primaryName, primaryTag) = resolveNameAndTagFast(primaryCall, primaryNum)

        val stage = when (primaryState) {
            Call.STATE_RINGING -> UiCallStage.INCOMING
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> UiCallStage.OUTGOING
            Call.STATE_ACTIVE, Call.STATE_HOLDING -> UiCallStage.ACTIVE
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> UiCallStage.ENDED
            else -> UiCallStage.ACTIVE
        }

        val waitingNum = if (waitingCall != null) {
            try {
                waitingCall.details?.handle?.schemeSpecificPart ?: ""
            } catch (_: Throwable) {
                ""
            }
        } else ""
        val waitingName = if (waitingCall != null) {
            resolveNameAndTagFast(waitingCall, waitingNum).first
        } else ""

        val heldName = if (secondaryHeld != null) {
            val hNum = try {
                secondaryHeld.details?.handle?.schemeSpecificPart ?: ""
            } catch (_: Throwable) {
                ""
            }
            resolveNameAndTagFast(secondaryHeld, hNum).first
        } else ""

        val connectMs = synchronized(trackedCalls) {
            callConnectMillisMap[primaryCall] ?: System.currentTimeMillis()
        }
        val wasConnected = synchronized(trackedCalls) {
            callConnectedMap[primaryCall] == true
        }
        val dir = synchronized(trackedCalls) {
            callDirections[primaryCall] ?: detectCallDirection(primaryCall, primaryState)
        }

        val nextState = _uiState.value.copy(
            number = primaryNum.ifBlank { _uiState.value.number },
            displayName = primaryName,
            roleTag = primaryTag,
            isUnknownCaller = primaryNum.isBlank(),
            stage = stage,
            isOnHold = (primaryState == Call.STATE_HOLDING),
            connectTimeMillis = connectMs,
            wasEverConnected = wasConnected,
            initialDirection = dir,
            hasWaitingCall = (waitingCall != null),
            waitingCallerName = waitingName,
            waitingCallerNumber = waitingNum,
            hasSecondaryHeldCall = (secondaryHeld != null),
            secondaryHeldCallerName = heldName
        )
        _uiState.value = nextState

        // Async contact enrichment if not yet in fast memory cache
        val context = inCallServiceRef?.applicationContext
        if (context != null && primaryNum.isNotBlank() && DialerRepository.findCachedContactByNumber(primaryNum) == null) {
            controllerScope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    DialerRepository.lookupContactByNumber(context, primaryNum)
                }
                if (resolved != null && _uiState.value.number == primaryNum) {
                    val enriched = _uiState.value.copy(
                        displayName = resolved.name,
                        roleTag = resolved.tag
                    )
                    _uiState.value = enriched
                    inCallServiceRef?.syncCallNotification(enriched, isScreenVisible = isCallActivityVisible)
                }
            }
        }

        // Proximity WakeLock: Turn screen off when held to ear during Outgoing/Active earpiece call
        val shouldHoldProximity = (stage == UiCallStage.OUTGOING || stage == UiCallStage.ACTIVE) &&
                !nextState.isSpeakerOn && !nextState.isBluetoothOn
        inCallServiceRef?.updateProximityWakeLock(shouldHold = shouldHoldProximity)

        // Handle Ringtone vs Call Waiting Beep
        if (stage == UiCallStage.INCOMING) {
            inCallServiceRef?.startIncomingRinging(primaryNum)
        } else if (waitingCall != null) {
            inCallServiceRef?.stopIncomingRinging()
            inCallServiceRef?.playCallWaitingBeep()
        } else {
            inCallServiceRef?.stopIncomingRinging()
        }

        if (stage == UiCallStage.ENDED) {
            inCallServiceRef?.cancelCallNotification()
        } else {
            inCallServiceRef?.syncCallNotification(nextState, isScreenVisible = isCallActivityVisible)
        }
    }

    /**
     * O(1) non-blocking contact resolution for instant UI frame rendering.
     */
    private fun resolveNameAndTagFast(call: Call, rawNumber: String): Pair<String, String> {
        val resolvedContact = DialerRepository.findCachedContactByNumber(rawNumber)
        val isUnknown = rawNumber.isBlank()
        val callerCnam = try {
            call.details?.callerDisplayName
        } catch (_: Throwable) {
            null
        }
        val displayName = when {
            isUnknown -> "UNKNOWN CALLER"
            resolvedContact != null -> resolvedContact.name
            !callerCnam.isNullOrBlank() -> callerCnam.uppercase()
            else -> rawNumber
        }
        val tag = when {
            isUnknown -> "Unverified Carrier ID"
            resolvedContact != null -> resolvedContact.tag
            else -> "Mobile Carrier"
        }
        return displayName to tag
    }

    private fun recordCompletedCallToSession(
        rawNumber: String,
        direction: CallType,
        wasConnected: Boolean,
        connectTimeMillis: Long
    ) {
        val cleanNum = rawNumber.trim()
        if (cleanNum.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        val finalType = when {
            direction == CallType.INCOMING && !wasConnected -> CallType.MISSED
            direction == CallType.INCOMING -> CallType.INCOMING
            else -> CallType.OUTGOING
        }

        val durationSec = if (wasConnected && connectTimeMillis > 0L) {
            ((nowMs - connectTimeMillis) / 1000L).coerceAtLeast(1L)
        } else {
            0L
        }

        val isUnansweredOutgoing = (finalType == CallType.OUTGOING && !wasConnected)
        val durationStr = when {
            finalType == CallType.MISSED -> "Missed"
            isUnansweredOutgoing -> "Not answered"
            else -> "${durationSec / 60}m ${String.format(Locale.US, "%02d", durationSec % 60)}s"
        }

        val matched = DialerRepository.findCachedContactByNumber(cleanNum)

        val newEntry = CallLogItem(
            id = "session_$nowMs",
            contactId = matched?.id ?: cleanNum,
            name = matched?.name ?: cleanNum.uppercase(),
            number = cleanNum,
            type = finalType,
            groupCount = 1,
            timestamp = timeFormat.format(Date(nowMs)),
            dateGroup = "TODAY",
            duration = durationStr,
            isSaved = matched != null,
            isUnansweredOutgoing = isUnansweredOutgoing,
            dateMillis = nowMs
        )

        synchronized(_sessionCallLogs) {
            val key = newEntry.normalizedPhoneKey
            _sessionCallLogs.removeAll { it.normalizedPhoneKey == key }
            _sessionCallLogs.add(0, newEntry)
            if (_sessionCallLogs.size > 15) {
                _sessionCallLogs.removeAt(_sessionCallLogs.lastIndex)
            }
        }
    }

    fun onAudioStateChanged(audioState: CallAudioState?) {
        audioState ?: return
        val speaker = audioState.route == CallAudioState.ROUTE_SPEAKER
        val bluetooth = audioState.route == CallAudioState.ROUTE_BLUETOOTH
        val next = _uiState.value.copy(
            isMuted = audioState.isMuted,
            isSpeakerOn = speaker,
            isBluetoothOn = bluetooth
        )
        _uiState.value = next

        val shouldHoldProximity = (next.stage == UiCallStage.OUTGOING || next.stage == UiCallStage.ACTIVE) &&
                !speaker && !bluetooth
        inCallServiceRef?.updateProximityWakeLock(shouldHold = shouldHoldProximity)
        if (next.stage != UiCallStage.ENDED) {
            inCallServiceRef?.syncCallNotification(next, isScreenVisible = isCallActivityVisible)
        }
    }

    // --- User Actions from CallActivity & Google-Style Heads-Up Call Popup ---

    fun answerCall(openCallScreen: Boolean = true) {
        inCallServiceRef?.stopIncomingRinging()
        val ringing = synchronized(trackedCalls) {
            trackedCalls.find { getCallStateSafely(it) == Call.STATE_RINGING } ?: trackedCalls.lastOrNull()
        }
        try {
            ringing?.answer(VideoProfile.STATE_AUDIO_ONLY)
        } catch (_: Throwable) {
        }
        val next = _uiState.value.copy(
            stage = UiCallStage.ACTIVE,
            connectTimeMillis = System.currentTimeMillis(),
            wasEverConnected = true,
            hasWaitingCall = false
        )
        _uiState.value = next
        if (openCallScreen && !isCallActivityVisible) {
            inCallServiceRef?.launchCallActivity()
        }
        inCallServiceRef?.syncCallNotification(next, isScreenVisible = isCallActivityVisible)
        notifyCallLogChanged()
    }

    fun answerWaitingCall() {
        val waiting = synchronized(trackedCalls) {
            trackedCalls.find { getCallStateSafely(it) == Call.STATE_RINGING }
        }
        try {
            waiting?.answer(VideoProfile.STATE_AUDIO_ONLY)
        } catch (_: Throwable) {
        }
        rebuildUiStateFromTrackedCalls()
    }

    fun declineWaitingCall() {
        val waiting = synchronized(trackedCalls) {
            trackedCalls.find { getCallStateSafely(it) == Call.STATE_RINGING }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                waiting?.reject(Call.REJECT_REASON_DECLINED)
            } else {
                @Suppress("DEPRECATION")
                waiting?.reject(false, null)
            }
        } catch (_: Throwable) {
            try {
                waiting?.disconnect()
            } catch (_: Throwable) {
            }
        }
        rebuildUiStateFromTrackedCalls()
    }

    fun swapActiveAndHeldCalls() {
        val calls = synchronized(trackedCalls) { trackedCalls.toList() }
        val holding = calls.find { getCallStateSafely(it) == Call.STATE_HOLDING }
        val active = calls.find { getCallStateSafely(it) == Call.STATE_ACTIVE }
        try {
            if (holding != null) {
                holding.unhold()
            } else if (active != null) {
                active.hold()
            }
        } catch (_: Throwable) {
        }
        rebuildUiStateFromTrackedCalls()
    }

    fun endOrDeclineCall() {
        simulatedFallbackJob?.cancel()
        inCallServiceRef?.stopIncomingRinging()
        val calls = synchronized(trackedCalls) { trackedCalls.toList() }
        if (calls.isNotEmpty()) {
            val target = calls.find { getCallStateSafely(it) == Call.STATE_RINGING }
                ?: calls.find { getCallStateSafely(it) == Call.STATE_ACTIVE }
                ?: calls.find {
                    val st = getCallStateSafely(it)
                    st == Call.STATE_DIALING || st == Call.STATE_CONNECTING || st == Call.STATE_SELECT_PHONE_ACCOUNT
                }
                ?: calls.last()

            val targetState = getCallStateSafely(target)
            try {
                if (targetState == Call.STATE_RINGING) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        target.reject(Call.REJECT_REASON_DECLINED)
                    } else {
                        @Suppress("DEPRECATION")
                        target.reject(false, null)
                    }
                } else {
                    target.disconnect()
                }
            } catch (_: Throwable) {
                try {
                    target.disconnect()
                } catch (_: Throwable) {
                }
            }

            // If this was the only tracked call, transition UI immediately for zero-lag Google Phone feel
            if (calls.size <= 1) {
                inCallServiceRef?.updateProximityWakeLock(shouldHold = false)
                inCallServiceRef?.cancelCallNotification()
                _uiState.value = _uiState.value.copy(
                    stage = UiCallStage.ENDED,
                    hasWaitingCall = false,
                    hasSecondaryHeldCall = false
                )
            }
        } else {
            inCallServiceRef?.updateProximityWakeLock(shouldHold = false)
            inCallServiceRef?.cancelCallNotification()
            _uiState.value = _uiState.value.copy(stage = UiCallStage.ENDED)
        }
        notifyCallLogChanged()
    }

    fun toggleMute() {
        val nextMute = !_uiState.value.isMuted
        try {
            inCallServiceRef?.setMuted(nextMute)
        } catch (_: Throwable) {
        }
        val nextState = _uiState.value.copy(isMuted = nextMute)
        _uiState.value = nextState
        if (nextState.stage != UiCallStage.ENDED) {
            inCallServiceRef?.syncCallNotification(nextState, isScreenVisible = isCallActivityVisible)
        }
    }

    fun toggleSpeaker() {
        val nextSpeaker = !_uiState.value.isSpeakerOn
        val route = if (nextSpeaker) {
            CallAudioState.ROUTE_SPEAKER
        } else {
            CallAudioState.ROUTE_WIRED_OR_EARPIECE
        }
        try {
            inCallServiceRef?.setAudioRoute(route)
        } catch (_: Throwable) {
        }
        val nextState = _uiState.value.copy(isSpeakerOn = nextSpeaker)
        _uiState.value = nextState
        val shouldHoldProximity = (nextState.stage == UiCallStage.OUTGOING || nextState.stage == UiCallStage.ACTIVE) &&
                !nextSpeaker && !nextState.isBluetoothOn
        inCallServiceRef?.updateProximityWakeLock(shouldHold = shouldHoldProximity)
        if (nextState.stage != UiCallStage.ENDED) {
            inCallServiceRef?.syncCallNotification(nextState, isScreenVisible = isCallActivityVisible)
        }
    }

    fun toggleHold() {
        val nextHold = !_uiState.value.isOnHold
        val target = synchronized(trackedCalls) {
            trackedCalls.find {
                val st = getCallStateSafely(it)
                st == Call.STATE_ACTIVE || st == Call.STATE_HOLDING
            }
        }
        try {
            if (target != null) {
                if (nextHold) target.hold() else target.unhold()
            }
        } catch (_: Throwable) {
        }
        val nextState = _uiState.value.copy(isOnHold = nextHold)
        _uiState.value = nextState
        if (nextState.stage != UiCallStage.ENDED) {
            inCallServiceRef?.syncCallNotification(nextState, isScreenVisible = isCallActivityVisible)
        }
    }

    fun sendDtmf(digit: Char) {
        val target = synchronized(trackedCalls) {
            trackedCalls.find { getCallStateSafely(it) == Call.STATE_ACTIVE } ?: trackedCalls.lastOrNull()
        }
        try {
            if (target != null) {
                target.playDtmfTone(digit)
                target.stopDtmfTone()
            }
        } catch (_: Throwable) {
        }
        val updated = (_uiState.value.dtmfHistory + digit).takeLast(14)
        _uiState.value = _uiState.value.copy(dtmfHistory = updated)
    }

    fun startSimulatedCall(
        number: String,
        stage: UiCallStage = UiCallStage.OUTGOING,
        isUnknown: Boolean = false
    ) {
        if (hasActiveTelecomCall()) return
        val matched = DialerRepository.findCachedContactByNumber(number)
        val cleanNum = if (isUnknown) "+44 7911 092831" else number.ifBlank { "+44 7700 900412" }
        val resolvedName = when {
            isUnknown -> "UNKNOWN CALLER"
            matched != null -> matched.name
            else -> cleanNum
        }

        _uiState.value = ActiveCallUiState(
            number = cleanNum,
            displayName = resolvedName,
            roleTag = if (isUnknown) "Unverified Number" else (matched?.tag ?: "Mobile Carrier"),
            isUnknownCaller = isUnknown,
            stage = stage,
            connectTimeMillis = System.currentTimeMillis(),
            wasEverConnected = (stage == UiCallStage.ACTIVE),
            initialDirection = if (stage == UiCallStage.INCOMING) CallType.INCOMING else CallType.OUTGOING
        )
        notifyCallLogChanged()
    }

    fun switchSimulatedStage(stage: UiCallStage, isUnknown: Boolean = _uiState.value.isUnknownCaller) {
        _uiState.value = _uiState.value.copy(
            stage = stage,
            isUnknownCaller = isUnknown,
            wasEverConnected = _uiState.value.wasEverConnected || (stage == UiCallStage.ACTIVE),
            connectTimeMillis = if (stage == UiCallStage.ACTIVE) {
                System.currentTimeMillis()
            } else {
                _uiState.value.connectTimeMillis
            }
        )
        notifyCallLogChanged()
    }
}

/**
 * [CallEngineService] — Google Phone-Grade InCallService Implementation:
 * 1. Displays High-Priority Heads-Up Call Popup (`IMPORTANCE_HIGH` + `CallStyle.forIncomingCall` +
 *    `FullScreenIntent`) when an incoming call arrives while the user is using any other app.
 * 2. Automatically wakes and launches full-screen [CallActivity] when the phone is locked/off,
 *    when placing an outgoing call, or when the user is inside the Mist Phone app.
 * 3. Plays Contact-Specific Custom Ringtone (or default phone ringtone) + vibration asynchronously
 *    on `Dispatchers.IO` with zero main-thread lag.
 * 4. Manages Proximity Screen-Off WakeLock during earpiece calls just like Google Phone.
 */
class CallEngineService : InCallService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ringtoneJob: Job? = null

    private var activeMediaPlayer: MediaPlayer? = null
    private var fallbackRingtone: Ringtone? = null
    private var activeVibrator: Vibrator? = null
    private var currentlyRingingNumber: String? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createCallNotificationChannels()
        CallStateController.attachService(this)
    }

    override fun onDestroy() {
        stopIncomingRinging()
        updateProximityWakeLock(shouldHold = false)
        cancelCallNotification()
        CallStateController.detachService()
        super.onDestroy()
    }

    fun launchCallActivity() {
        try {
            val intent = Intent(this, CallActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "launchCallActivity error", t)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        try {
            CallStateController.onTelecomCallAdded(call)

            val currentState = CallStateController.getCallStateSafely(call)
            val uiState = CallStateController.uiState.value
            if (uiState.stage == UiCallStage.ENDED) return

            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isScreenLockedOrOff = (powerManager?.isInteractive == false) ||
                    (keyguardManager?.isKeyguardLocked == true)

            val isUserInsideDialerApp =
                CallStateController.isMainActivityVisible || CallStateController.isCallActivityVisible

            // Always sync Foreground Call Notification first (required for Android 10-15 FullScreenIntent & Heads-Up Popup)
            syncCallNotification(uiState, isScreenVisible = CallStateController.isCallActivityVisible)

            // Launch full-screen CallActivity immediately if:
            // - Outgoing call, OR
            // - Screen is locked/off, OR
            // - User is already inside the Dialer app
            // Otherwise (user is using another app on unlocked screen), the IMPORTANCE_HIGH CallStyle
            // notification shows the Google Phone-style Heads-Up Incoming Call Popup at the top of the screen!
            if (currentState != Call.STATE_RINGING || isScreenLockedOrOff || isUserInsideDialerApp) {
                launchCallActivity()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onCallAdded error", t)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        try {
            CallStateController.onTelecomCallRemoved(call)
        } catch (t: Throwable) {
            Log.e(TAG, "onCallRemoved error", t)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        try {
            CallStateController.onAudioStateChanged(audioState)
        } catch (_: Throwable) {
        }
    }

    // ========================================================================
    // PROXIMITY SENSOR WAKE-LOCK (Turns off screen when held to ear)
    // ========================================================================

    fun updateProximityWakeLock(shouldHold: Boolean) {
        try {
            if (shouldHold) {
                if (proximityWakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                        proximityWakeLock = pm.newWakeLock(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                            "MistDialer:ProximityWakeLock"
                        ).apply {
                            setReferenceCounted(false)
                        }
                    }
                }
                if (proximityWakeLock?.isHeld == false) {
                    proximityWakeLock?.acquire(4 * 60 * 60 * 1000L)
                }
            } else {
                if (proximityWakeLock?.isHeld == true) {
                    proximityWakeLock?.release()
                }
            }
        } catch (_: Throwable) {
        }
    }

    // ========================================================================
    // ASYNC CONTACT-SPECIFIC CUSTOM RINGTONE & VIBRATION ENGINE (ZERO UI LAG)
    // ========================================================================

    @Suppress("DEPRECATION")
    fun startIncomingRinging(rawNumber: String) {
        if (currentlyRingingNumber == rawNumber &&
            (activeMediaPlayer?.isPlaying == true || fallbackRingtone?.isPlaying == true || ringtoneJob?.isActive == true)
        ) {
            return
        }
        stopIncomingRinging()
        currentlyRingingNumber = rawNumber

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        // Start Vibration immediately
        if (ringerMode == AudioManager.RINGER_MODE_NORMAL || ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            try {
                val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                val pattern = longArrayOf(0L, 800L, 800L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    vib?.vibrate(pattern, 0)
                }
                activeVibrator = vib
            } catch (_: Throwable) {
            }
        }

        if (ringerMode != AudioManager.RINGER_MODE_NORMAL) return

        // Prepare MediaPlayer / Ringtone on Dispatchers.IO so main thread never stutters
        ringtoneJob = serviceScope.launch {
            val (preparedPlayer, preparedRingtone) = withContext(Dispatchers.IO) {
                val customUri: Uri? = DialerRepository.getCustomRingtoneUriForIncomingNumber(
                    applicationContext,
                    rawNumber
                )
                val targetUri: Uri = customUri
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

                var mp: MediaPlayer? = null
                var fallback: Ringtone? = null
                try {
                    mp = MediaPlayer().apply {
                        setDataSource(applicationContext, targetUri)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        isLooping = true
                        prepare()
                    }
                } catch (_: Throwable) {
                    mp?.release()
                    mp = null
                }

                if (mp == null) {
                    try {
                        val r = RingtoneManager.getRingtone(applicationContext, targetUri)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            r?.audioAttributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            r?.isLooping = true
                        }
                        fallback = r
                    } catch (_: Throwable) {
                    }
                }
                mp to fallback
            }

            if (currentlyRingingNumber != rawNumber ||
                CallStateController.uiState.value.stage != UiCallStage.INCOMING
            ) {
                try {
                    preparedPlayer?.release()
                } catch (_: Throwable) {
                }
                return@launch
            }

            try {
                if (preparedPlayer != null) {
                    preparedPlayer.start()
                    activeMediaPlayer = preparedPlayer
                } else if (preparedRingtone != null) {
                    preparedRingtone.play()
                    fallbackRingtone = preparedRingtone
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun playCallWaitingBeep() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
                tg.startTone(ToneGenerator.TONE_SUP_CALL_WAITING, 600)
            } catch (_: Throwable) {
            }
        }
    }

    fun playCallEndTone() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 55)
                tg.startTone(ToneGenerator.TONE_PROP_PROMPT, 180)
            } catch (_: Throwable) {
            }
        }
    }

    fun stopIncomingRinging() {
        currentlyRingingNumber = null
        ringtoneJob?.cancel()
        ringtoneJob = null

        val playerToRelease = activeMediaPlayer
        activeMediaPlayer = null
        val ringtoneToStop = fallbackRingtone
        fallbackRingtone = null

        if (playerToRelease != null || ringtoneToStop != null) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    playerToRelease?.stop()
                } catch (_: Throwable) {
                }
                try {
                    playerToRelease?.release()
                } catch (_: Throwable) {
                }
                try {
                    ringtoneToStop?.stop()
                } catch (_: Throwable) {
                }
            }
        }

        try {
            activeVibrator?.cancel()
        } catch (_: Throwable) {
        }
        activeVibrator = null
    }

    // ========================================================================
    // GOOGLE-PHONE-GRADE HEADS-UP INCOMING POPUP & ONGOING CALL NOTIFICATION
    // ========================================================================

    private fun createCallNotificationChannels() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

                // 1. HIGH-PRIORITY Channel for Incoming Calls & Call Waiting Heads-Up Banner Popup
                val incomingChannel = NotificationChannel(
                    CHANNEL_INCOMING_ID,
                    "Incoming Calls & Heads-Up Popup",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Displays heads-up incoming call popup banner and full-screen lockscreen call UI"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    // Ringtone & Vibration are handled by CallEngineService's custom ringtone engine
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                }

                // 2. Ongoing Active Call Channel (Status bar timer pill + notification shade controls)
                val ongoingChannel = NotificationChannel(
                    CHANNEL_ONGOING_ID,
                    "Ongoing Active Calls",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows live call timer and Speaker/Mute/End Call controls in status bar"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(false)
                }

                nm.createNotificationChannel(incomingChannel)
                nm.createNotificationChannel(ongoingChannel)
            }
        } catch (_: Throwable) {
        }
    }

    private fun buildReceiverPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, CallNotificationReceiver::class.java).apply {
            this.action = action
            setPackage(packageName)
        }
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun syncCallNotification(state: ActiveCallUiState, isScreenVisible: Boolean = false) {
        try {
            if (state.stage == UiCallStage.ENDED) {
                cancelCallNotification()
                return
            }

            createCallNotificationChannels()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val openCallIntent = Intent(this, CallActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            val openCallPendingIntent = PendingIntent.getActivity(
                this,
                200,
                openCallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val answerPendingIntent = buildReceiverPendingIntent(ACTION_ANSWER_CALL, 201)
            val declineOrEndPendingIntent = buildReceiverPendingIntent(ACTION_DECLINE_OR_END_CALL, 202)
            val speakerPendingIntent = buildReceiverPendingIntent(ACTION_TOGGLE_SPEAKER, 203)
            val mutePendingIntent = buildReceiverPendingIntent(ACTION_TOGGLE_MUTE, 204)

            val isRinging = (state.stage == UiCallStage.INCOMING) || state.hasWaitingCall
            val isActive = state.stage == UiCallStage.ACTIVE

            // Use IMPORTANCE_HIGH channel when ringing so Android shows the Heads-Up Incoming Call Popup!
            val targetChannelId = if (isRinging) CHANNEL_INCOMING_ID else CHANNEL_ONGOING_ID

            val callerTitle = if (state.hasWaitingCall) {
                state.waitingCallerName.ifBlank { state.waitingCallerNumber }
            } else {
                state.displayName.ifBlank { state.number }
            }

            val subtitle = when {
                state.hasWaitingCall -> "Incoming Call Waiting · ${state.waitingCallerNumber}"
                state.stage == UiCallStage.INCOMING -> "Incoming Call · ${state.number}"
                isActive && state.isOnHold -> "Call On Hold · ${state.number}"
                isActive -> "Ongoing Call · ${state.number}"
                else -> "Dialing · ${state.number}"
            }

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, targetChannelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this).setPriority(
                    if (isRinging) Notification.PRIORITY_MAX else Notification.PRIORITY_DEFAULT
                )
            }

            builder
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(callerTitle)
                .setContentText(subtitle)
                .setContentIntent(openCallPendingIntent)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)

            if (isRinging) {
                // FullScreenIntent guarantees Heads-Up Popup when unlocked AND full-screen launch when locked
                builder.setFullScreenIntent(openCallPendingIntent, true)
            }

            if (isActive && !state.hasWaitingCall) {
                builder
                    .setUsesChronometer(true)
                    .setWhen(state.connectTimeMillis)
                    .setShowWhen(true)
            }

            // Native Android 12+ (API 31+) CallStyle Notification (Google Phone style pill & heads-up card)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callerPerson = Person.Builder()
                    .setName(callerTitle)
                    .setImportant(true)
                    .build()

                if (isRinging) {
                    builder.style = Notification.CallStyle.forIncomingCall(
                        callerPerson,
                        declineOrEndPendingIntent,
                        answerPendingIntent
                    )
                } else {
                    builder.style = Notification.CallStyle.forOngoingCall(
                        callerPerson,
                        declineOrEndPendingIntent
                    )
                    val speakerLabel = if (state.isSpeakerOn) "Speaker ON" else "Speaker"
                    val muteLabel = if (state.isMuted) "Unmute" else "Mute"
                    builder.addAction(
                        Notification.Action.Builder(
                            android.R.drawable.ic_lock_silent_mode_off,
                            speakerLabel,
                            speakerPendingIntent
                        ).build()
                    )
                    builder.addAction(
                        Notification.Action.Builder(
                            android.R.drawable.ic_btn_speak_now,
                            muteLabel,
                            mutePendingIntent
                        ).build()
                    )
                }
            } else {
                if (isRinging) {
                    builder.addAction(
                        Notification.Action.Builder(
                            android.R.drawable.sym_action_call,
                            "Accept",
                            answerPendingIntent
                        ).build()
                    )
                    builder.addAction(
                        Notification.Action.Builder(
                            android.R.drawable.ic_menu_close_clear_cancel,
                            "Decline",
                            declineOrEndPendingIntent
                        ).build()
                    )
                } else {
                    val speakerLabel = if (state.isSpeakerOn) "Speaker ON" else "Speaker"
                    val muteLabel = if (state.isMuted) "Unmute" else "Mute"
                    builder.addAction(
                        Notification.Action.Builder(
                            android.R.drawable.ic_lock_silent_mode_off,
                            speakerLabel,
                            speakerPendingIntent
                        ).build()
                    )
                    builder.addAction(
                        Notification.Action.Builder(
                            android.R.drawable.ic_btn_speak_now,
                            muteLabel,
                            mutePendingIntent
                        ).build()
                    )
                    builder.addAction(
                        Notification.Action.Builder(
                            android.R.drawable.ic_menu_close_clear_cancel,
                            "End Call",
                            declineOrEndPendingIntent
                        ).build()
                    )
                }
            }

            val notification = builder.build()

            var startedForeground = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        CALL_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                } else {
                    startForeground(CALL_NOTIFICATION_ID, notification)
                }
                startedForeground = true
            } catch (_: Throwable) {
                startedForeground = false
            }

            if (!startedForeground) {
                try {
                    nm.notify(CALL_NOTIFICATION_ID, notification)
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "syncCallNotification safe catch", t)
        }
    }

    fun cancelCallNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(CALL_NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "CallEngineService"
        private const val CHANNEL_INCOMING_ID = "mist_phone_incoming_high_v3"
        private const val CHANNEL_ONGOING_ID = "mist_phone_ongoing_low_v3"
        private const val CALL_NOTIFICATION_ID = 4041

        const val ACTION_ANSWER_CALL = "com.example.mistdialer.ACTION_ANSWER_CALL"
        const val ACTION_DECLINE_OR_END_CALL = "com.example.mistdialer.ACTION_DECLINE_OR_END_CALL"
        const val ACTION_TOGGLE_SPEAKER = "com.example.mistdialer.ACTION_TOGGLE_SPEAKER"
        const val ACTION_TOGGLE_MUTE = "com.example.mistdialer.ACTION_TOGGLE_MUTE"
    }
}
