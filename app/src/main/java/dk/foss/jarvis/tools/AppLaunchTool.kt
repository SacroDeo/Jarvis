package dk.foss.jarvis.tools

import android.content.ActivityNotFoundException
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
 * Only apps that are launchable (have a MAIN/LAUNCHER intent) are considered.
 * [android.permission.QUERY_ALL_PACKAGES] is NOT needed; we query by intent,
 * which is permitted without special permissions.
 */
object AppLaunchTool {

    private const val TAG       = "JarvisAppLaunch"
    private const val FUZZY_MIN = 2 // minimum query length for word-start / contains matching

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
            Log.w(TAG, "queryLaunchableApps returned empty — package visibility may be restricted " +
                "(check <queries> in AndroidManifest.xml, targetSdk=${context.applicationInfo.targetSdkVersion})")
            return Result.Failure("Could not read installed apps.")
        }
        Log.d(TAG, "queryLaunchableApps found ${launchables.size} apps, " +
            "matching against query='${query.take(30)}'")

        val ranked = rank(query.lowercase(), launchables, pm)
        if (ranked.isEmpty()) {
            Log.i(TAG, "No app matched query='${query.take(30)}' (total apps scanned: ${launchables.size})")
            return Result.NotFound(query)
        }

        // If the top-ranked bucket has multiple ties, ask the user to pick one.
        val topScore = ranked[0].second
        val topTier = ranked.filter { it.second == topScore }
        if (topTier.size > 1) {
            val labels = topTier.map { it.first.loadLabel(pm).toString() }
            return Result.MultipleMatches(labels)
        }

        val best = topTier[0].first
        val label = best.loadLabel(pm).toString()
        val pkg = best.activityInfo.packageName

        val launchIntent = pm.getLaunchIntentForPackage(pkg)
            ?: return Result.Failure("$label cannot be launched.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(launchIntent)
            Log.i(TAG, "Opened app: $label ($pkg)")
            Result.Success(label)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Activity not found for $pkg", e)
            Result.Failure("$label is not installed or cannot be opened.")
        } catch (e: Exception) {
            Log.w(TAG, "Error launching $pkg", e)
            Result.Failure("Could not open $label.")
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun queryLaunchableApps(pm: PackageManager): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // Use flags=0 (no additional filters) so that all apps advertising
        // ACTION_MAIN + CATEGORY_LAUNCHER are returned.
        //
        // MATCH_DEFAULT_ONLY was the previous flag; it implicitly adds a
        // CATEGORY_DEFAULT requirement which most launcher activities do not
        // declare.  On stock Android the extra category is tolerated, but on
        // some OEM variants (e.g. Realme ColorOS) it causes the query to return
        // an empty list, making every app appear "not found".
        return pm.queryIntentActivities(intent, 0)
    }

    /**
     * Assign a priority score to each app whose label matches [queryLower].
     * Lower score = better match.  Apps with no match are excluded.
     *
     * The scoring logic is extracted into [scoreMatch] so it can be verified
     * by unit tests without an Android runtime.
     */
    private fun rank(
        queryLower: String,
        apps: List<ResolveInfo>,
        pm: PackageManager,
    ): List<Pair<ResolveInfo, Int>> {
        return apps.mapNotNull { ri ->
            val labelLower = ri.loadLabel(pm).toString().lowercase()
            val score = scoreMatch(queryLower, labelLower) ?: return@mapNotNull null
            Pair(ri, score)
        }.sortedBy { it.second }
    }

    /**
     * Return the match priority for [queryLower] against [labelLower]
     * (both already lowercased by the caller).
     *
     * 0 = exact  |  1 = label-starts-with  |  2 = word-starts-with  |  3 = contains
     * null = no match
     *
     * Exposed as `internal` so unit tests can exercise matching without an
     * Android [PackageManager].
     */
    internal fun scoreMatch(queryLower: String, labelLower: String): Int? = when {
        queryLower.isEmpty() -> null
        labelLower == queryLower -> 0
        labelLower.startsWith(queryLower) -> 1
        queryLower.length >= FUZZY_MIN &&
            labelLower.split(Regex("\\s+")).any { it.startsWith(queryLower) } -> 2
        queryLower.length >= FUZZY_MIN && labelLower.contains(queryLower) -> 3
        else -> null
    }
}
