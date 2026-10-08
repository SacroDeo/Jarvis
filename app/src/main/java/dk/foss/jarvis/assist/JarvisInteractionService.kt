package dk.foss.jarvis.assist

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.voice.VoiceInteractionService
import android.util.Log
import dk.foss.jarvis.wake.WakeListener
import dk.foss.jarvis.wake.WakeWordService

/**
 * Jarvis VoiceInteractionService — the always-running anchor for the assistant.
 * When set as default assistant, the system keeps this service bound and alive.
 * onReady() starts the wake-word listener; onShutdown() stops it.
 * On wake detection, calls showSession() to launch the voice interaction session.
 *
 * The system selects the active VoiceInteractionService via
 * Settings.Secure.VOICE_INTERACTION_SERVICE (separate from the RoleManager
 * ROLE_ASSISTANT concept). Use isActiveService() to check our status.
 */
class JarvisInteractionService : VoiceInteractionService() {

    private var wakeListener: WakeListener? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VoiceInteractionService created — package=${applicationContext.packageName}")
    }

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "onReady — Jarvis is the active VoiceInteractionService, starting wake word listener")

        wakeListener = object : WakeListener {
            override fun onWakeDetected() {
                Log.i(TAG, "Wake detected via VoiceInteractionService callback → showSession()")
                showSession(null, 0)
            }
        }

        // VoiceInteractionService gets a background microphone exemption when it is the
        // system-selected assistant; startWithListener() uses that exemption to launch a
        // microphone foreground service from the background.
        WakeWordService.startWithListener(this, wakeListener!!)
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.i(TAG, "onShutdown — stopping wake word listener")
        if (wakeListener != null) {
            WakeWordService.stop(this)
            wakeListener = null
        }
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        Log.i(TAG, "onLaunchVoiceAssistFromKeyguard → showSession()")
        showSession(null, 0)
    }

    companion object {
        private const val TAG = "JarvisAssist"

        /**
         * Returns true if OUR VoiceInteractionService is the one currently selected
         * by the Android system (i.e. Settings.Secure.VOICE_INTERACTION_SERVICE points
         * to this component).
         *
         * Uses the official public API VoiceInteractionService.isActiveService(Context,
         * ComponentName) (API 21+). This is the definitive check — it reads the system
         * setting directly. Do NOT use RoleManager.isRoleHeld(ROLE_ASSISTANT) here:
         * the ROLE_ASSISTANT role and the active VoiceInteractionService are separate
         * concepts; on many devices holding ROLE_ASSISTANT does NOT activate the VIS.
         */
        fun isActiveService(context: Context): Boolean {
            return try {
                // ComponentName(context, Class) uses context.packageName as the package,
                // which correctly handles the debug applicationIdSuffix ('.debug').
                val component = ComponentName(context, JarvisInteractionService::class.java)
                val active = VoiceInteractionService.isActiveService(context, component)
                Log.d(TAG, "isActiveService: $active — component=${component.flattenToShortString()}")
                active
            } catch (e: Exception) {
                Log.w(TAG, "isActiveService check failed", e)
                false
            }
        }
    }
}
