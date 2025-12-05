package com.android.dialer.helpers

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.android.dialer.extensions.getStateCompat
import com.android.dialer.extensions.hasCapability
import com.android.dialer.extensions.isConference
import com.android.dialer.extensions.isOutgoing
import com.android.dialer.models.AudioRoute
import java.util.concurrent.CopyOnWriteArraySet

class CallManager {
    companion object {

        @SuppressLint("StaticFieldLeak")
        var inCallService: InCallService? = null
        private var call: Call? = null
        private val calls = mutableListOf<Call>()
        private val listeners = CopyOnWriteArraySet<CallManagerListener>()

        // Manual redial
        private var lastOutgoingHandle: Uri? = null
        var pendingRedialHandle: Uri? = null

        // ---------------- Auto-redial ----------------
        private var userHungUp = false
        private var autoRedialAttempts = 0
        var maxAutoRedialAttempts: Int = 3
        var autoRedialDelayMs: Long = 2000L
        // --------------------------------------------

        fun onCallAdded(call: Call) {
            this.call = call
            calls.add(call)

            // Reset auto-redial state
            userHungUp = false
            autoRedialAttempts = 0

            if (call.isOutgoing()) lastOutgoingHandle = call.details.handle

            for (listener in listeners) listener.onPrimaryCallChanged(call)

            call.registerCallback(object : Call.Callback() {
                override fun onStateChanged(call: Call, state: Int) {
                    updateState()
                    if (state == Call.STATE_ACTIVE) {
                        autoRedialAttempts = 0
                        userHungUp = false
                    }
                }

                override fun onDetailsChanged(call: Call, details: Call.Details) { updateState() }

                override fun onConferenceableCallsChanged(
                    call: Call,
                    conferenceableCalls: MutableList<Call>
                ) { updateState() }
            })
        }

        fun onCallRemoved(call: Call) {
            val wasPrimaryCall = call == getPrimaryCall()
            calls.remove(call)
            val handle = call.details.handle

            // Auto-redial logic
            if (!userHungUp && handle != null && autoRedialAttempts < maxAutoRedialAttempts) {
                autoRedialAttempts++
                Handler().postDelayed({ placeCall(handle) }, autoRedialDelayMs)
            }

            // Manual pending redial button
            if (pendingRedialHandle != null && handle == pendingRedialHandle) {
                placeCall(pendingRedialHandle!!)
                pendingRedialHandle = null
            }

            if (calls.isEmpty()) {
                autoRedialAttempts = 0
                userHungUp = false
            }

            updateState()
            for (listener in listeners) listener.onStateChanged()
        }

        // Manual disconnect triggers userHangUp
        fun manualDisconnect() {
            userHungUp = true
            call?.disconnect()
        }

        fun redial() {
            val outgoingCall = calls.find {
                it.getStateCompat() == Call.STATE_DIALING ||
                    it.getStateCompat() == Call.STATE_CONNECTING
            }
            val handle = outgoingCall?.details?.handle ?: lastOutgoingHandle ?: return

            if (outgoingCall != null) {
                pendingRedialHandle = handle
                userHungUp = true
                outgoingCall.disconnect()
            } else {
                placeCall(handle)
            }
        }

        private fun placeCall(handle: Uri) {
            try {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = handle
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                inCallService?.startActivity(intent)
            } catch (e: Exception) { e.printStackTrace() }
        }

        fun onAudioStateChanged(audioState: CallAudioState) {
            val route = AudioRoute.fromRoute(audioState.route) ?: return
            for (listener in listeners) listener.onAudioStateChanged(route)
        }

        fun getPhoneState(): PhoneState {
            return when (calls.size) {
                0 -> NoCall
                1 -> SingleCall(calls.first())
                2 -> {
                    val active = calls.find { it.getStateCompat() == Call.STATE_ACTIVE }
                    val newCall = calls.find { it.getStateCompat() == Call.STATE_CONNECTING || it.getStateCompat() == Call.STATE_DIALING }
                    val onHold = calls.find { it.getStateCompat() == Call.STATE_HOLDING }
                    when {
                        active != null && newCall != null -> TwoCalls(newCall, active)
                        newCall != null && onHold != null -> TwoCalls(newCall, onHold)
                        active != null && onHold != null -> TwoCalls(active, onHold)
                        else -> TwoCalls(calls[0], calls[1])
                    }
                }
                else -> {
                    val conference = calls.find { it.isConference() } ?: return NoCall
                    val secondCall = if (conference.children.size + 1 != calls.size) {
                        calls.filter { !it.isConference() }
                            .subtract(conference.children.toSet())
                            .firstOrNull()
                    } else null

                    if (secondCall == null) SingleCall(conference)
                    else {
                        val newState = secondCall.getStateCompat()
                        if (newState == Call.STATE_ACTIVE || newState == Call.STATE_CONNECTING || newState == Call.STATE_DIALING)
                            TwoCalls(secondCall, conference)
                        else TwoCalls(conference, secondCall)
                    }
                }
            }
        }

        fun getPhoneSize() = calls.size

        private fun getCallAudioState() = inCallService?.callAudioState

        fun getSupportedAudioRoutes(): Array<AudioRoute> {
            return AudioRoute.entries.filter {
                val mask = getCallAudioState()?.supportedRouteMask ?: return@filter false
                mask and it.route == it.route
            }.toTypedArray()
        }

        fun getCallAudioRoute() = AudioRoute.fromRoute(getCallAudioState()?.route)
        fun setAudioRoute(newRoute: Int) { inCallService?.setAudioRoute(newRoute) }

        private fun updateState() {
            val primaryCall = when (val state = getPhoneState()) {
                is NoCall -> null
                is SingleCall -> state.call
                is TwoCalls -> state.active
            }
            var notify = true
            if (primaryCall == null) call = null
            else if (primaryCall != call) {
                call = primaryCall
                for (listener in listeners) listener.onPrimaryCallChanged(primaryCall)
                notify = false
            }
            if (notify) for (listener in listeners) listener.onStateChanged()
            calls.removeAll { it.getStateCompat() == Call.STATE_DISCONNECTED }
        }

        fun getPrimaryCall(): Call? = call
        fun getConferenceCalls(): List<Call> = calls.find { it.isConference() }?.children ?: emptyList()
        fun accept() = call?.answer(VideoProfile.STATE_AUDIO_ONLY)

        fun reject(rejectWithMessage: Boolean = false, textMessage: String? = null) {
            call?.let {
                val state = getState()
                if (state == Call.STATE_RINGING) {
                    userHungUp = true
                    it.reject(rejectWithMessage, textMessage)
                } else if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) {
                    manualDisconnect()
                }
            }
        }

        fun toggleHold(): Boolean {
            val onHold = getState() == Call.STATE_HOLDING
            if (onHold) call?.unhold() else call?.hold()
            return !onHold
        }

        fun swap() {
            if (calls.size > 1) calls.find { it.getStateCompat() == Call.STATE_HOLDING }?.unhold()
        }

        fun merge() {
            val conf = call ?: return
            val confList = conf.conferenceableCalls
            if (confList.isNotEmpty()) conf.conference(confList.first())
            else if (conf.hasCapability(Call.Details.CAPABILITY_MERGE_CONFERENCE)) conf.mergeConference()
        }

        fun addListener(listener: CallManagerListener) = listeners.add(listener)
        fun removeListener(listener: CallManagerListener) = listeners.remove(listener)
        fun getState() = getPrimaryCall()?.getStateCompat()
        fun keypad(char: Char) {
            call?.playDtmfTone(char)
            Handler().postDelayed({ call?.stopDtmfTone() }, DIALPAD_TONE_LENGTH_MS)
        }
    }
}

interface CallManagerListener {
    fun onStateChanged()
    fun onAudioStateChanged(audioState: AudioRoute)
    fun onPrimaryCallChanged(call: Call)
}

sealed class PhoneState
data object NoCall : PhoneState()
class SingleCall(val call: Call) : PhoneState()
class TwoCalls(val active: Call, val onHold: Call) : PhoneState()
