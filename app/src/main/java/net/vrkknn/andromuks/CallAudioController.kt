package net.vrkknn.andromuks

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Makes a call sound like a call: in-call audio mode, the right speaker, and a dark screen when the
 * phone is at your ear.
 *
 * WebRTC inside the WebView happily plays a voice call out of the loudspeaker at media volume, which
 * is what "it works but it doesn't feel like a phone call" actually means. Fixing it is three
 * things — the audio mode (so the volume keys control call volume), the routing (earpiece for a
 * voice call, loudspeaker for video, and any headset beats both), and the proximity sensor.
 *
 * **Why this is native rather than driven by Element Call.** Element Call can hand audio-device
 * control to its host: with the `controlledAudioDevices` URL parameter it drives `window.controls`,
 * which is how Element X routes call audio. That route is closed to us — Element X loads Element Call
 * as the WebView's top-level document, so `controls` is same-document, while we load it in a
 * cross-origin iframe inside our widget host page, where neither `evaluateJavascript` nor the host
 * page can reach it. Everything here therefore works one layer down, at [AudioManager], which needs
 * no cooperation from the page at all.
 */
internal class CallAudioController(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Turns the screen off while the phone is held to the ear, as the dialer does. */
    private val proximityWakeLock by lazy {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        powerManager
            ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "andromuks:call_proximity")
    }

    private var active = false
    private var voiceCall = false

    /** Re-applies the preferred route when a headset is plugged in or pulled out mid-call. */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = applyPreferredRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = applyPreferredRoute()
    }

    fun onCallStarted(isVoiceCall: Boolean) {
        voiceCall = isVoiceCall
        if (active) {
            applyPreferredRoute()
            return
        }
        active = true
        // Element X's workaround, and worth keeping: from Android 13 the in-communication mode is
        // what makes the volume keys control call volume, but setting it on 12 and below breaks
        // audio-device switching outright.
        audioManager.mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AudioManager.MODE_IN_COMMUNICATION
        } else {
            AudioManager.MODE_NORMAL
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        applyPreferredRoute()
        Log.i(TAG, "Call audio started (voice=$isVoiceCall, mode=${audioManager.mode})")
    }

    fun onCallStopped() {
        if (!active) return
        active = false
        releaseProximityLock()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
                .onFailure { Log.w(TAG, "Could not clear the communication device", it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        Log.i(TAG, "Call audio stopped")
    }

    /**
     * A plugged-in headset always wins — nobody wants their call on the loudspeaker because the app
     * had an opinion. Otherwise a voice call goes to the earpiece and a video call to the speaker,
     * which is what every phone does and what the user implicitly asked for by picking one.
     */
    private fun applyPreferredRoute() {
        if (!active) return
        val devices = availableDevices()
        val headset = devices.firstOrNull { it.type in HEADSET_TYPES }
        val preferred = headset
            ?: devices.firstOrNull { it.type == if (voiceCall) AudioDeviceInfo.TYPE_BUILTIN_EARPIECE else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: devices.firstOrNull()
        if (preferred == null) {
            Log.w(TAG, "No usable audio device to route the call to")
            return
        }
        select(preferred)
        if (preferred.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) acquireProximityLock() else releaseProximityLock()
    }

    private fun availableDevices(): List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.availableCommunicationDevices
    } else {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink && it.type in ROUTABLE_TYPES }
    }

    private fun select(device: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                if (!audioManager.setCommunicationDevice(device)) {
                    Log.w(TAG, "setCommunicationDevice refused device ${device.type}")
                }
            }.onFailure { Log.w(TAG, "Could not set the communication device", it) }
        } else {
            @Suppress("DEPRECATION")
            run {
                audioManager.isSpeakerphoneOn = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                audioManager.isBluetoothScoOn = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        }
    }

    private fun acquireProximityLock() {
        val lock = proximityWakeLock ?: return
        if (!lock.isHeld) {
            @Suppress("WakelockTimeout")
            lock.acquire()
        }
    }

    private fun releaseProximityLock() {
        val lock = proximityWakeLock ?: return
        if (lock.isHeld) lock.release()
    }

    private companion object {
        const val TAG = "CallAudioController"

        /** Anything the user physically plugged in or paired, in descending order of intent. */
        val HEADSET_TYPES = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        )

        val ROUTABLE_TYPES = HEADSET_TYPES + listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        )
    }
}
