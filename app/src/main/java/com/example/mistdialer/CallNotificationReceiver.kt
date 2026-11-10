package com.example.mistdialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest-registered BroadcastReceiver for instant, zero-delay response when the user taps
 * Accept, Decline, Speaker, or Mute on the Heads-Up Incoming Call Popup or Ongoing Call Notification.
 */
class CallNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            CallEngineService.ACTION_ANSWER_CALL -> {
                if (CallStateController.uiState.value.hasWaitingCall) {
                    CallStateController.answerWaitingCall()
                } else {
                    CallStateController.answerCall(openCallScreen = false)
                }
            }
            CallEngineService.ACTION_DECLINE_OR_END_CALL -> {
                if (CallStateController.uiState.value.hasWaitingCall) {
                    CallStateController.declineWaitingCall()
                } else {
                    CallStateController.endOrDeclineCall()
                }
            }
            CallEngineService.ACTION_TOGGLE_SPEAKER -> {
                CallStateController.toggleSpeaker()
            }
            CallEngineService.ACTION_TOGGLE_MUTE -> {
                CallStateController.toggleMute()
            }
        }
    }
}
