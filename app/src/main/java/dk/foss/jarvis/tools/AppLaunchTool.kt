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
            Log.w(TAG, "No launchable apps found")
            return Result.Failure("Could not read installed apps.")
        }

        val ranked = rank(query.lowercase(), launchables, pm)
        if (ranked.isEmpty()) return Result.NotFound(query)

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
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }

    /**
     * Assign a priority score to each app whose label matches [queryLower].
     * Lower score = better match.  Apps with no match are excluded.
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
