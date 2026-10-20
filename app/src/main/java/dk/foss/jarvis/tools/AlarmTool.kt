package dk.foss.jarvis.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

/**
 * Executes a "set alarm/reminder" action by delegating to the system Clock app
 * via [AlarmClock.ACTION_SET_ALARM].
 *
 * No special Android permission is required — the Clock app owns the alarm data
 * and handles it internally.  [android.permission.SET_ALARM] is a normal-level
 * permission declared in the manifest as a precaution on older API levels.
 *
 * Implementation notes:
 * - [AlarmClock.EXTRA_SKIP_UI] = true sets the alarm silently (best experience).
 * - If the device's Clock app does not support SKIP_UI, the intent still works
 *   but opens the Clock UI pre-filled; [ActivityNotFoundException] is NOT thrown.
 * - Phone numbers, contact names, and other private data are never passed here.
 */
object AlarmTool {

    private const val TAG = "JarvisAlarmTool"

    sealed class Result {
        data class Success(val description: String) : Result()
        data class InvalidTime(val hour: Int, val minute: Int) : Result()
        data class Failure(val reason: String) : Result()
    }

    /**
     * Set an alarm for [hour]:[minute] with optional [label].
     *
     * @param context  Application context (never leaks to the model).
     * @param hour     Hour in 24-hour format (0–23).
     * @param minute   Minute (0–59).
     * @param label    Human-readable alarm label shown by the Clock app.
     * @return         [Result.Success] with a log-safe description, or an error subtype.
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
            // Note: startActivity returning without an exception means the Clock app
            // received the intent.  It does NOT confirm that the alarm was actually saved —
            // some OEM Clock apps open a confirmation screen even when EXTRA_SKIP_UI=true.
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
