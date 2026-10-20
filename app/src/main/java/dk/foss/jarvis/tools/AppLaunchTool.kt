package dk.foss.jarvis.tools

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log

/**
 * Executes an "open application" action by resolving the user's natural-language
 * app name against the list of installed launchable apps and firing the launch intent.
 *
 * Resolution strategy (by priority):
 *  1. Exact case-insensitive label match.
 *  2. Label starts with the query.
 *  3. Any word in the label starts with the query (minimum 2 characters).
 *  4. Label contains the query as a substring.
 *
 * **Android 11+ (API 30) package-visibility fix:**
 * [PackageManager.getLaunchIntentForPackage] queries by package name, which requires
 * either QUERY_ALL_PACKAGES or an explicit <queries> entry — neither of which we declare.
 * Even though [queryIntentActivities] with ACTION_MAIN + CATEGORY_LAUNCHER is
 * "auto-visible" (no declaration needed), `getLaunchIntentForPackage` is NOT auto-visible
 * and silently returns null on Android 11+ for most installed apps.
 *
 * The fix: build the launch intent directly from [ResolveInfo.activityInfo], which we
 * already have from the launcher query. This bypasses package-visibility entirely.
 */
object AppLaunchTool {

    private const val TAG       = "JarvisAppLaunch"
    private const val FUZZY_MIN = 2

    sealed class Result {
        data class Success(val appLabel: String) : Result()
        data class NotFound(val query: String) : Result()
        data class MultipleMatches(val labels: List<String>) : Result()
        data class Failure(val reason: String) : Result()
    }

    /**
     * Open the application whose label best matches [appName].
     *
     * @param context  Application context.
     * @param appName  Natural-language app name from the model (e.g. "Chrome", "Maps").
     */
    fun open(context: Context, appName: String): Result {
        val query = appName.trim()
        if (query.isBlank()) return Result.Failure("No app name provided.")

        val pm = context.packageManager
        val launchables: List<ResolveInfo> = queryLaunchableApps(pm)
        if (launchables.isEmpty()) {
            Log.w(TAG, "No launchable apps found via MAIN+LAUNCHER query")
            return Result.Failure("Could not read installed apps.")
        }

        val ranked = rank(query.lowercase(), launchables, pm)
        if (ranked.isEmpty()) return Result.NotFound(query)

        // If the top-ranked bucket has multiple ties, ask the user to pick one.
        val topScore = ranked[0].second
        val topTier  = ranked.filter { it.second == topScore }
        if (topTier.size > 1) {
            val labels = topTier.map { it.first.loadLabel(pm).toString() }
            return Result.MultipleMatches(labels)
        }

        val best  = topTier[0].first
        val label = best.loadLabel(pm).toString()

        // ── Build launch intent from ResolveInfo.activityInfo ──────────────────
        // IMPORTANT: Do NOT use pm.getLaunchIntentForPackage() here.
        //
        // On Android 11+ (API 30+), getLaunchIntentForPackage() requires the target
        // package to be in the app's "visible packages list" (either via QUERY_ALL_PACKAGES
        // or a <queries> manifest entry). Even though queryIntentActivities(MAIN+LAUNCHER)
        // is auto-visible, getLaunchIntentForPackage is NOT, and silently returns null for
        // most installed apps without a manifest declaration.
        //
        // The fix: construct the explicit ComponentName from activityInfo — the same
        // activity that the launcher query already located. An explicit ComponentName
        // bypasses package-visibility checks entirely.
        val ai  = best.activityInfo
        val pkg = ai.packageName
        val cls = if (ai.name.startsWith(".")) pkg + ai.name else ai.name

        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(pkg, cls)
            addCategory(Intent.CATEGORY_LAUNCHER)
            // NEW_TASK: required when starting from Application context.
            // RESET_TASK_IF_NEEDED: bring existing task to front with correct state.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        return try {
            context.startActivity(launchIntent)
            // Log package+activity at debug level (not label, which is display-visible).
            Log.i(TAG, "Launched $pkg/$cls via explicit ComponentName")
            Result.Success(label)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ActivityNotFound for $pkg/$cls", e)
            Result.Failure("$label is not installed or cannot be opened.")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException launching $pkg", e)
            Result.Failure("Permission denied when opening $label.")
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error launching $pkg", e)
            Result.Failure("Could not open $label.")
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Return all apps that have a MAIN/LAUNCHER activity.
     *
     * Using an intent query (not getInstalledApplications) means QUERY_ALL_PACKAGES
     * is not needed — ACTION_MAIN + CATEGORY_LAUNCHER is in the auto-visible set
     * on Android 11+.
     */
    private fun queryLaunchableApps(pm: PackageManager): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }

    /**
     * Assign a priority score to each app whose label matches [queryLower].
     * Lower score = better match. Apps with no match are excluded.
     */
    private fun rank(
        queryLower: String,
        apps: List<ResolveInfo>,
        pm: PackageManager,
    ): List<Pair<ResolveInfo, Int>> {
        return apps.mapNotNull { ri ->
            val labelLower = ri.loadLabel(pm).toString().lowercase()
            val score = when {
                labelLower == queryLower -> 0
                labelLower.startsWith(queryLower) -> 1
                queryLower.length >= FUZZY_MIN &&
                    labelLower.split(Regex("\\s+")).any { it.startsWith(queryLower) } -> 2
                queryLower.length >= FUZZY_MIN && labelLower.contains(queryLower) -> 3
                else -> return@mapNotNull null
            }
            Pair(ri, score)
        }.sortedBy { it.second }
    }
}
