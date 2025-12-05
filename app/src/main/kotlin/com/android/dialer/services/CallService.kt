package com.android.dialer.services

import android.Manifest
import android.content.Context
import android.hardware.SensorManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import androidx.annotation.RequiresPermission
import com.android.dialer.activities.CallActivity
import com.android.dialer.extensions.config
import com.android.dialer.extensions.isOutgoing
import com.android.dialer.extensions.powerManager
import com.android.dialer.helpers.*
import com.android.dialer.models.Events
import com.android.dialer.sim.SimStateManager
import com.squareup.seismic.ShakeDetector
import org.greenrobot.eventbus.EventBus

class CallService : InCallService() {

    private val callNotificationManager by lazy { CallNotificationManager(this) }
    private var shakeDetector: ShakeDetector? = null
    private var isNear = false
    private var proximityListener: android.hardware.SensorEventListener? = null
    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                callNotificationManager.cancelNotification()
                stopShakeDetector()
            } else callNotificationManager.setupNotification()
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)

        // Start shake detector for incoming calls
        if (config.shakeToAnswer &&
            !call.isOutgoing() &&
            call.state == Call.STATE_RINGING &&
            !powerManager.isInteractive // prevent shake use when user sees screen
        ) {
            startProximitySensor()
            startShakeDetector(call)
        }
        when {
            !powerManager.isInteractive -> try {
                startActivity(CallActivity.getStartIntent(this))
                callNotificationManager.setupNotification(true)
            } catch (_: Exception) {
                callNotificationManager.setupNotification()
            }

            call.isOutgoing() -> try {
                startActivity(CallActivity.getStartIntent(this, needSelectSIM = call.details.accountHandle == null))
                callNotificationManager.setupNotification(true)
            } catch (_: Exception) {
                callNotificationManager.setupNotification()
            }

            config.showIncomingCallsFullScreen -> try {
                startActivity(CallActivity.getStartIntent(this))
                callNotificationManager.setupNotification(true)
            } catch (_: Exception) {
                callNotificationManager.setupNotification()
            }

            else -> callNotificationManager.setupNotification()
        }

        if (!call.isOutgoing() && !powerManager.isInteractive && config.flashForAlerts)
            MyCameraImpl.newInstance(this).toggleSOS()
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)

        stopProximitySensor()
        stopShakeDetector()

        val slotIndex = getSlotIndexFromHandle(call.details.accountHandle)
        val connectTime = call.details.connectTimeMillis
        val minutesUsed = if (connectTime > 0) (((System.currentTimeMillis() - connectTime) + 59_999) / 60_000).toInt() else 0
        if (slotIndex >= 0 && minutesUsed > 0) {
            SimStateManager.addUsedMinutes(slotIndex, minutesUsed)
            SimStateManager.saveAll(this)
        }

        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        EventBus.getDefault().post(Events.RefreshCallLog)

        if (CallManager.pendingRedialHandle == null) {
            if (CallManager.getPhoneState() == NoCall) {
                CallManager.inCallService = null
                callNotificationManager.cancelNotification()
            } else {
                callNotificationManager.setupNotification()
                if (wasPrimaryCall) startActivity(CallActivity.getStartIntent(this))
            }
        } else callNotificationManager.setupNotification()

        if (config.flashForAlerts) MyCameraImpl.newInstance(this).stopSOS()
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState?.let { CallManager.onAudioStateChanged(it) }
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun getSlotIndexFromHandle(handle: PhoneAccountHandle?): Int {
        if (handle == null) return -1
        val sm = getSystemService(SubscriptionManager::class.java) ?: return -1
        val list = sm.activeSubscriptionInfoList ?: return -1
        for (info in list) if (info.subscriptionId.toString() == handle.id || info.iccId == handle.id) return info.simSlotIndex
        for (info in list) if (info.carrierName?.toString() == handle.id) return info.simSlotIndex
        return -1
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProximitySensor()
        stopShakeDetector()
        callNotificationManager.cancelNotification()
        if (config.flashForAlerts) MyCameraImpl.newInstance(this).stopSOS()
    }

    // ---------- Shake Detector ----------
    private fun startShakeDetector(call: Call) {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shakeDetector = ShakeDetector {
            // Vibrate immediately when shake is detected
            vibrateOnce()

            if (!isNear) {   // PROXIMITY CHECK
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                stopShakeDetector()
                stopProximitySensor()
            }
        }
        shakeDetector?.start(sm)
    }

    private fun stopShakeDetector() {
        shakeDetector?.stop()
        shakeDetector = null
    }

    private fun startProximitySensor() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val proximity = sm.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY) ?: return

        proximityListener = object : android.hardware.SensorEventListener {
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

            override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                event ?: return
                // near = value < maximum range
                isNear = event.values[0] < proximity.maximumRange
            }
        }

        sm.registerListener(
            proximityListener,
            proximity,
            SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    private fun stopProximitySensor() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximityListener?.let { sm.unregisterListener(it) }
        proximityListener = null
        isNear = false
    }

    private fun vibrateOnce() {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        80, // vibration length (ms)
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(80)
            }
        } catch (_: Exception) {}
    }


}
