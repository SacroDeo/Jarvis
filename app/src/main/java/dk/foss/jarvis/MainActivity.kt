package dk.foss.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.foss.jarvis.assist.JarvisInteractionService
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.ui.ChatScreen
import dk.foss.jarvis.ui.ChatViewModel
import dk.foss.jarvis.ui.ConversationScreen
import dk.foss.jarvis.ui.ConversationViewModel
import dk.foss.jarvis.ui.HistoryScreen
import dk.foss.jarvis.ui.HistoryViewModel
import dk.foss.jarvis.ui.JarvisTheme
import dk.foss.jarvis.ui.SettingsScreen
import dk.foss.jarvis.wake.WakeWordService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class Screen { Chat, Settings, Conversation, History }

class MainActivity : ComponentActivity() {

    // Incremented each time the assistant is triggered; observed by Compose to
    // jump into conversation mode (works for both cold start and onNewIntent).
    private var assistEpoch by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen awake while Jarvis is in the foreground.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Edge-to-edge dark: transparent bars, dark background, light icons.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
        window.setBackgroundDrawableResource(android.R.color.black)
        if (isAssistIntent(intent)) {
            assistEpoch++
            showOverLockScreen()
        }
        rearmWakeWord()

        setContent {
            JarvisTheme {
                var screen by remember {
                    mutableStateOf(if (assistEpoch > 0) Screen.Conversation else Screen.Chat)
                }
                LaunchedEffect(assistEpoch) {
                    if (assistEpoch > 0) screen = Screen.Conversation
                }
                when (screen) {
                    Screen.Chat -> {
                        val vm: ChatViewModel = viewModel()
                        ChatScreen(
                            vm = vm,
                            onOpenSettings = { screen = Screen.Settings },
                            onOpenVoice = { screen = Screen.Conversation },
                            onOpenHistory = { screen = Screen.History },
                        )
                    }
                    Screen.Settings -> {
                        BackHandler { screen = Screen.Chat }
                        SettingsScreen(onBack = { screen = Screen.Chat })
                    }
                    Screen.Conversation -> {
                        BackHandler { screen = Screen.Chat }
                        val cvm: ConversationViewModel = viewModel()
                        ConversationScreen(
                            vm = cvm,
                            assistTrigger = assistEpoch,
                            // Move the task to the background (home screen) instead of merely
                            // navigating to the Chat screen.  The activity is preserved in
                            // recents; WakeWordService and VoiceInteractionService keep running.
                            // When the wake word fires again, onNewIntent bumps assistEpoch and
                            // LaunchedEffect returns to Screen.Conversation cleanly.
                            onExit = { screen = Screen.Chat; moveTaskToBack(true) },
                        )
                    }
                    Screen.History -> {
                        BackHandler { screen = Screen.Chat }
                        val hvm: HistoryViewModel = viewModel()
                        HistoryScreen(
                            vm = hvm,
                            onOpen = { screen = Screen.Chat },
                            onBack = { screen = Screen.Chat },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isAssistIntent(intent)) {
            assistEpoch++
            showOverLockScreen()
        }
    }

    /**
     * Appear over the lock screen and turn the display on (wake-word / assist launch).
     *
     * [setShowWhenLocked] allows this activity to draw on top of the keyguard window.
     * The keyguard is NOT dismissed — the user's PIN/pattern/biometric protection
     * remains fully engaged.  JARVIS shows on top so the user can interact by voice
     * (or tap the on-screen mic / close buttons) without unlocking the phone first.
     *
     * [setTurnScreenOn] wakes the display if it was off, which is required for the
     * wake-word path (screen off → wake word fires → display should turn on automatically).
     *
     * Both flags are API 27+ (minSdk is 29 so no version guard needed, but we keep
     * the explicit check as documentation of the API level requirement).
     *
     * Previous implementation also called [KeyguardManager.requestDismissKeyguard],
     * which forced a PIN/biometric challenge before JARVIS could start listening —
     * blocking voice commands that do not require an unlocked phone (alarms, calls,
     * spoken replies).  That call has been removed.  If a specific command later
     * requires authenticated UI access, it can request keyguard dismissal at that
     * point via the appropriate system API.
     */
    private fun showOverLockScreen() {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    /** If the wake word is enabled and VoiceInteractionService is NOT active, start legacy listener. */
    private fun rearmWakeWord() {
        lifecycleScope.launch {
            val s = SettingsStore(this@MainActivity).settings.first()
            val micOk = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (s.wakeEnabled && micOk) {
                if (JarvisInteractionService.isActiveService(this@MainActivity)) {
                    Log.d("MainActivity", "Jarvis is default assistant — VoiceInteractionService owns wake listener")
                } else {
                    runCatching { WakeWordService.start(applicationContext) }
                }
            }
        }
    }

    private fun isAssistIntent(i: Intent?): Boolean =
        i?.getBooleanExtra(EXTRA_FROM_ASSIST, false) == true ||
            i?.action == Intent.ACTION_ASSIST

    companion object {
        const val EXTRA_FROM_ASSIST = "from_assist"
    }
}
