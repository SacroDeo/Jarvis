package dk.foss.jarvis.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Executes deep-link and intent-based navigation actions without an AccessibilityService.
 *
 * Each function fires a standard Android intent and returns immediately. Whether the
 * target app actually opens, loads the content, or starts playback cannot be confirmed
 * at this phase — callers (and the AI system prompt) must not claim success beyond
 * "the intent was dispatched".
 *
 * Privacy: search queries and URLs are NOT transmitted to the AI backend. They are passed
 * directly from the AI's structured response envelope to the Android intent layer on-device.
 *
 * Supported actions:
 *  • [openUrl]        — open any validated https/http URL in the default browser or matching app.
 *  • [youtubeSearch]  — open the YouTube app (or browser fallback) on search results for a query.
 *  • [browserSearch]  — open the default browser on a Google search-results page.
 *  • [spotifyPlay]    — open Spotify (or browser fallback) on a search page for a query.
 */
object DeepLinkTool {

    private const val TAG = "JarvisDeepLink"

    sealed class Result {
        /** Intent dispatched successfully. [description] is a log-safe one-liner. */
        data class Success(val description: String) : Result()

        /**
         * The targeted app is not installed or did not handle the URI.
         * [fallbackUrl] is non-null when a browser fallback was attempted — callers should
         * tell the user something like "Spotify isn't installed; I opened its website instead."
         */
        data class AppNotFound(val appName: String, val fallbackUrl: String?) : Result()

        /** A non-recoverable error (invalid URL, permission denied, etc.). */
        data class Failure(val reason: String) : Result()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public actions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Open [rawUrl] in whatever app handles https/http on this device (usually the
     * default browser, but YouTube/Spotify/etc. may intercept their own domains).
     *
     * Bare hostnames like "example.com" are promoted to "https://example.com".
     * The URL is validated before dispatch; an unrecognised scheme returns [Result.Failure].
     */
    fun openUrl(context: Context, rawUrl: String): Result {
        val url = normalizeUrl(rawUrl)
            ?: return Result.Failure("\"${rawUrl.take(80)}\" is not a valid URL.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return fireIntent(context, intent, "URL")
    }

    /**
     * Open the YouTube app — if installed — on its search results page for [query].
     *
     * Strategy:
     *  1. Fire `ACTION_VIEW` with the YouTube search URL constrained to the YouTube package.
     *     This opens the YouTube app directly without going through the browser chooser.
     *  2. If the YouTube app is not installed (ActivityNotFoundException), fall back to the
     *     same URL without a package constraint so any browser handles it.
     *
     * Note: this lands on the search results page. The user taps a result to start playback.
     * Playback is not confirmed by this function.
     */
    fun youtubeSearch(context: Context, query: String): Result {
        if (query.isBlank()) return Result.Failure("No search query provided.")
        val encoded = Uri.encode(query.trim())
        val webUrl = "https://www.youtube.com/results?search_query=$encoded"

        // Attempt 1: YouTube app directly
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
            setPackage("com.google.android.youtube")
        }
        return try {
            context.startActivity(appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "YouTube app opened for search query (len=${query.length})")
            Result.Success("Opened YouTube search results for \"${query.take(40)}\".")
        } catch (_: ActivityNotFoundException) {
            Log.i(TAG, "YouTube app not found, falling back to browser")
            // Attempt 2: any browser
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
            when (val r = fireIntent(context, browserIntent, "YouTube (browser fallback)")) {
                is Result.Success -> Result.Success(
                    "YouTube is not installed; opened search in the browser instead."
                )
                else -> r
            }
        }
    }

    /**
     * Open a Google web search for [query] in the default browser.
     *
     * Uses a plain `https://www.google.com/search?q=...` URL so any installed browser
     * (Chrome, Firefox, Samsung Internet, etc.) can handle it without a package constraint.
     */
    fun browserSearch(context: Context, query: String): Result {
        if (query.isBlank()) return Result.Failure("No search query provided.")
        val encoded = Uri.encode(query.trim())
        val url = "https://www.google.com/search?q=$encoded"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return fireIntent(context, intent, "browser search")
    }

    /**
     * Open the Spotify app — if installed — on its search page for [query].
     *
     * Strategy:
     *  1. Fire the `spotify:search:<encoded_query>` deep link with the Spotify package
     *     constraint. This opens the Spotify search screen directly.
     *  2. If Spotify is not installed or does not handle the URI, fall back to the Spotify
     *     web player search URL in the default browser.
     *
     * Note: whether the track actually starts playing cannot be confirmed. The result
     * description says "opened Spotify search" — not "playing".
     */
    fun spotifyPlay(context: Context, query: String): Result {
        if (query.isBlank()) return Result.Failure("No search query provided.")
        val encodedSpotify = Uri.encode(query.trim())  // path-safe encoding for URI
        val encodedWeb     = Uri.encode(query.trim())  // same encoding for web URL

        // Attempt 1: Spotify app deep link
        val spotifyUri = "spotify:search:$encodedSpotify"
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUri)).apply {
            setPackage("com.spotify.music")
        }
        return try {
            context.startActivity(appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "Spotify app opened via deep link for query (len=${query.length})")
            Result.Success("Opened Spotify with a search for \"${query.take(40)}\".")
        } catch (_: ActivityNotFoundException) {
            Log.i(TAG, "Spotify app not found, falling back to browser")
            // Attempt 2: Spotify web player
            val webUrl = "https://open.spotify.com/search/$encodedWeb"
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
            when (fireIntent(context, browserIntent, "Spotify (browser fallback)")) {
                is Result.Success ->
                    Result.AppNotFound("Spotify", webUrl)  // tells caller to explain the fallback
                else ->
                    Result.Failure("Spotify is not installed and the web player could not be opened.")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validate and normalise a raw URL string.
     *
     * Rules:
     *  - Strings that already start with "https://" or "http://" (case-insensitive) are
     *    accepted as-is after a basic host check.
     *  - Bare hostnames without a scheme (e.g. "example.com", "www.bbc.co.uk/news") are
     *    promoted to "https://…" provided they contain a dot and no whitespace and carry
     *    no existing "://" separator.
     *  - Everything else returns null.
     *
     * Pure string logic only — no android.net.Uri — so this method can be exercised by
     * JVM unit tests without an Android runtime.
     *
     * Exposed as `internal` so unit tests can call it directly.
     */
    internal fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase()
        val withScheme: String = when {
            lower.startsWith("https://") || lower.startsWith("http://") -> trimmed
            // Bare hostname/path: no scheme separator, has a dot, no whitespace
            !trimmed.contains("://") &&
                trimmed.contains('.') &&
                !trimmed.contains(' ') -> "https://$trimmed"
            else -> return null
        }

        // Basic structural check: scheme must be http/https and host must be non-empty.
        // Derived from string split — no android.net.Uri dependency.
        val afterScheme = withScheme.substringAfter("://")
        val host = afterScheme
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
            .trim()
        if (host.isEmpty() || !host.contains('.')) return null

        // Reject obviously unsafe schemes that could slip through if the raw string
        // started with "http://" but isn't actually an HTTP URL (e.g. mis-typed input).
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "https" && scheme != "http") return null

        return withScheme
    }

    /**
     * Dispatch [intent] via [Context.startActivity] and translate exceptions into [Result].
     * [FLAG_ACTIVITY_NEW_TASK] is added unconditionally (required for non-Activity contexts).
     */
    private fun fireIntent(context: Context, intent: Intent, label: String): Result {
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "$label intent dispatched successfully")
            Result.Success("$label opened.")
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "$label: no activity found for intent")
            Result.AppNotFound(label, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "$label: security exception", e)
            Result.Failure("Permission denied opening $label.")
        } catch (e: Exception) {
            Log.w(TAG, "$label: unexpected error launching intent", e)
            Result.Failure("Could not open $label.")
        }
    }
}
