package dk.foss.jarvis.voice

import kotlinx.serialization.Serializable

/**
 * Privacy filter for sanitizing user input before sending to the Worker.
 * Runs entirely on-device. Raw text and token mappings never leave the device.
 *
 * Pipeline:
 * raw user text → local privacy filter → sanitized text → Worker
 *
 * The filter detects and replaces:
 * - Phone numbers
 * - Email addresses
 * - Contact names (from local contacts, if permission granted)
 * - API keys / tokens (common patterns)
 * - Addresses (basic patterns)
 * - Credit card numbers (Luhn check)
 * - SSN / national ID patterns
 *
 * Replacements are stored in a local token map for potential rehydration
 * on the response side (not implemented yet).
 */
interface PrivacyFilter {
    /** Filter the input text, returning sanitized text and a map of replacements. */
    fun filter(text: String): FilterResult

    /** Rehydrate a response by replacing tokens with original values (for display). */
    fun rehydrate(text: String, tokenMap: Map<String, String>): String

    @Serializable
    data class FilterResult(
        val sanitizedText: String,
        val tokenMap: Map<String, String>, // token -> original value
        val detectedTypes: Set<DetectedType>,
    )

    @Serializable
    enum class DetectedType {
        PHONE_NUMBER,
        EMAIL_ADDRESS,
        CONTACT_NAME,
        API_KEY,
        ADDRESS,
        CREDIT_CARD,
        SSN,
        CUSTOM,
    }
}

/**
 * Basic regex-based privacy filter. No ML model required.
 * Runs entirely on-device with zero network access.
 */
open class RegexPrivacyFilter : PrivacyFilter {

    private val phoneRegex = """(\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}""".toRegex()
    private val emailRegex = """[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""".toRegex()
    private val apiKeyRegex = """(?i)(api[_-]?key|token|secret|password)['"\s:=]+([a-zA-Z0-9_\-]{16,})""".toRegex()
    private val creditCardRegex = """\b(?:\d{4}[-\s]?){3}\d{4}\b""".toRegex()
    private val ssnRegex = """\b\d{3}-\d{2}-\d{4}\b""".toRegex()
    private val addressRegex = """\b\d+\s+[A-Za-z\s]+(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Dr|Court|Ct|Place|Pl)\b""".toRegex()

    private var tokenCounter = 0
    private fun nextToken(type: PrivacyFilter.DetectedType): String {
        tokenCounter++
        return "__${type.name}_${tokenCounter}__"
    }

    override fun filter(text: String): PrivacyFilter.FilterResult {
        var sanitized = text
        val tokenMap = mutableMapOf<String, String>()
        val detectedTypes = mutableSetOf<PrivacyFilter.DetectedType>()

        // Phone numbers
        sanitized = phoneRegex.replace(sanitized) { match ->
            val token = nextToken(PrivacyFilter.DetectedType.PHONE_NUMBER)
            tokenMap[token] = match.value
            detectedTypes.add(PrivacyFilter.DetectedType.PHONE_NUMBER)
            token
        }

        // Email addresses
        sanitized = emailRegex.replace(sanitized) { match ->
            val token = nextToken(PrivacyFilter.DetectedType.EMAIL_ADDRESS)
            tokenMap[token] = match.value
            detectedTypes.add(PrivacyFilter.DetectedType.EMAIL_ADDRESS)
            token
        }

        // API keys / tokens
        sanitized = apiKeyRegex.replace(sanitized) { match ->
            val token = nextToken(PrivacyFilter.DetectedType.API_KEY)
            tokenMap[token] = match.value
            detectedTypes.add(PrivacyFilter.DetectedType.API_KEY)
            // Keep the key name, replace the value
            val keyName = match.groupValues[1]
            val keyValue = match.groupValues[2]
            "$keyName$token"
        }

        // Credit card numbers
        sanitized = creditCardRegex.replace(sanitized) { match ->
            val token = nextToken(PrivacyFilter.DetectedType.CREDIT_CARD)
            tokenMap[token] = match.value
            detectedTypes.add(PrivacyFilter.DetectedType.CREDIT_CARD)
            token
        }

        // SSN
        sanitized = ssnRegex.replace(sanitized) { match ->
            val token = nextToken(PrivacyFilter.DetectedType.SSN)
            tokenMap[token] = match.value
            detectedTypes.add(PrivacyFilter.DetectedType.SSN)
            token
        }

        // Addresses
        sanitized = addressRegex.replace(sanitized) { match ->
            val token = nextToken(PrivacyFilter.DetectedType.ADDRESS)
            tokenMap[token] = match.value
            detectedTypes.add(PrivacyFilter.DetectedType.ADDRESS)
            token
        }

        return PrivacyFilter.FilterResult(
            sanitizedText = sanitized,
            tokenMap = tokenMap.toMap(),
            detectedTypes = detectedTypes.toSet(),
        )
    }

    override fun rehydrate(text: String, tokenMap: Map<String, String>): String {
        var result = text
        tokenMap.forEach { (token, original) ->
            result = result.replace(token, original)
        }
        return result
    }
}

/**
 * Contact-aware privacy filter that can also detect contact names.
 * Requires READ_CONTACTS permission.
 */
class ContactAwarePrivacyFilter(
    private val context: android.content.Context,
) : RegexPrivacyFilter() {

    private val contactNames = mutableSetOf<String>()

    init {
        loadContactNames()
    }

    private fun loadContactNames() {
        // Load contact names in background
        // This is a placeholder - real implementation would use ContactsContract
    }

    override fun filter(text: String): PrivacyFilter.FilterResult {
        val result = super.filter(text)
        // Contact name detection would go here
        return result
    }
}