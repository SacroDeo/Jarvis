package dk.foss.jarvis.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

/**
 * Executes alarm-related actions by delegating to the system Clock app
 * via [AlarmClock] intents.
 *
 * **What Android actually supports (and what we honestly report):**
 *
 * SET:     [AlarmClock.ACTION_SET_ALARM] — asks the Clock app to create an alarm.
 *          EXTRA_SKIP_UI=true requests silent creation; some OEM Clocks show UI anyway.
 *          `startActivity()` returning without exception means the intent was accepted —
 *          NOT that the alarm was definitely saved.  We are honest about this.
 *
 * DISMISS: [AlarmClock.ACTION_DISMISS_ALARM] (API 23+) — dismisses an alarm that is
 *          CURRENTLY RINGING.  This CANNOT delete a scheduled future alarm.
 *          No public Android API allows an ordinary app to delete future alarms.
 *
 * SHOW:    [AlarmClock.ACTION_SHOW_ALARMS] — opens the Clock app's alarm list so
 *          the user can review and delete alarms themselves.  This is the only
 *          reliable way to cancel a future alarm from outside the Clock app.
 *
 * No special Android permissions are required for these intents.
 * [com.android.alarm.permission.SET_ALARM] is declared in the manifest as a
 * normal-level permission for pre-API-23 compatibility (auto-granted by the system).
 */
object AlarmTool {

    private const val TAG = "JarvisAlarmTool"

    sealed class Result {
        data class Success(val description: String) : Result()
        data class InvalidTime(val hour: Int, val minute: Int) : Result()
        data class Failure(val reason: String) : Result()
    }

    /**
     * Request the Clock app to set an alarm for [hour]:[minute] with [label].
     *
     * Returns [Result.Success] when the intent was accepted by the system.
     * This does NOT confirm the alarm was saved — see class KDoc.
     */
    fun set(context: Context, hour: Int, minute: Int, label: String): Result {
        if (hour !in 0..23 || minute !in 0..59) {
            return Result.InvalidTime(hour, minute)
        }

        val effectiveLabel = label.ifBlank { "Alarm" }.take(80)

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, effectiveLabel)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            val timeStr = "%02d:%02d".format(hour, minute)
            val desc = "Alarm intent sent to Clock app for $timeStr (label: $effectiveLabel). " +
                "Android cannot confirm the alarm was saved without user interaction."
            Log.i(TAG, "ACTION_SET_ALARM sent for $timeStr label=$effectiveLabel")
            Result.Success(desc)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No Clock app found to handle ACTION_SET_ALARM", e)
            Result.Failure("No clock application found to set the alarm.")
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception setting alarm", e)
            Result.Failure("Permission denied when setting alarm.")
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error setting alarm", e)
            Result.Failure("Could not set alarm.")
        }
    }

    /**
     * Attempt to dismiss an alarm that is currently ringing.
     *
     * [AlarmClock.ACTION_DISMISS_ALARM] (API 23+) only works for alarms that
     * are actively ringing or snoozed at the moment of the call.  It CANNOT
     * cancel or delete a scheduled future alarm.
     *
     * If [label] is provided, the search is narrowed to alarms with that label.
     * If null, the intent tries to dismiss any currently ringing alarm.
     *
     * Returns [DismissResult.Opened] if the intent was accepted (alarm may be
     * dismissed or the Clock UI may open), or [DismissResult.Failure] on error.
     */
    sealed class DismissResult {
        /** Intent accepted. The ringing alarm may have been dismissed, or the Clock UI opened. */
        data class Opened(val note: String) : DismissResult()
        /** Intent was not handled — no ringing alarm found, or no Clock app. */
        data class Failure(val reason: String) : DismissResult()
    }

    fun dismissRinging(context: Context, label: String? = null): DismissResult {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // ALARM_SEARCH_MODE_LABEL narrows to alarms with the given label.
            // When label is null we use ALL mode to catch any ringing alarm.
            if (!label.isNullOrBlank()) {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
            } else {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
            }
        }
        return try {
            context.startActivity(intent)
            Log.i(TAG, "ACTION_DISMISS_ALARM sent (label=$label)")
            DismissResult.Opened(
                "Dismiss request sent to Clock app. This only works for an alarm that is " +
                "currently ringing or snoozed — it cannot cancel a future scheduled alarm."
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No Clock app to handle ACTION_DISMISS_ALARM", e)
            DismissResult.Failure("No clock application found to dismiss the alarm.")
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing alarm", e)
            DismissResult.Failure("Could not dismiss alarm.")
        }
    }

    /**
     * Open the Clock app's alarm list so the user can view and manage their alarms.
     *
     * This is the only reliable way for an app to help a user cancel a future
     * alarm without Accessibility Service access.
     */
    fun showAlarms(context: Context): Boolean {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Log.i(TAG, "ACTION_SHOW_ALARMS sent")
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No Clock app to handle ACTION_SHOW_ALARMS", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error showing alarms", e)
            false
        }
    }

    /** Human-readable 12-hour time string for TTS ("7:00 AM", "2:30 PM"). */
    fun formatTimeTts(hour: Int, minute: Int): String {
        val period = if (hour < 12) "AM" else "PM"
        val h12 = when {
            hour == 0  -> 12
            hour <= 12 -> hour
            else       -> hour - 12
        }
        return if (minute == 0) "$h12 $period" else "$h12:%02d $period".format(minute)
    }
}
