package com.zektopic.cctvapp.web

/**
 * HTTP Basic authentication and request-origin checks for the on-device web server.
 *
 * Kept free of Android APIs so it can be exercised by plain JVM unit tests --
 * this is security-critical code and needs to be cheap to test.
 */
object WebAuth {

    /** Characters used for generated credentials. Excludes look-alikes (0/O, 1/l/I). */
    private const val PASSWORD_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    /**
     * Decodes an `Authorization: Basic <base64>` header into a username/password pair.
     * Returns null for anything malformed rather than throwing -- callers treat null as
     * "not authenticated".
     */
    fun parseBasicHeader(header: String?): Pair<String, String>? {
        if (header == null) return null
        val trimmed = header.trim()
        if (!trimmed.regionMatches(0, "Basic ", 0, 6, ignoreCase = true)) return null

        val encoded = trimmed.substring(6).trim()
        if (encoded.isEmpty()) return null

        val decoded = decodeBase64(encoded)?.toString(Charsets.UTF_8) ?: return null

        // Only the FIRST colon separates the fields; passwords may contain colons.
        val separator = decoded.indexOf(':')
        if (separator < 0) return null
        return Pair(decoded.substring(0, separator), decoded.substring(separator + 1))
    }

    /**
     * Decodes standard Base64, returning null for anything invalid.
     *
     * Hand-rolled rather than using `java.util.Base64` (API 26, above this app's minSdk
     * of 24 -- it would crash on Android 7) or `android.util.Base64` (a non-functional
     * stub under JVM unit tests, and this is security code that must stay testable).
     */
    fun decodeBase64(input: String): ByteArray? {
        val cleaned = input.filterNot { it == '\n' || it == '\r' }
        if (cleaned.isEmpty() || cleaned.length % 4 != 0) return null

        val output = java.io.ByteArrayOutputStream(cleaned.length / 4 * 3)
        var buffer = 0
        var bitsCollected = 0

        for ((index, character) in cleaned.withIndex()) {
            if (character == '=') {
                // Padding is only legal in the final quantum.
                if (index < cleaned.length - 2) return null
                continue
            }

            val value = BASE64_ALPHABET.indexOf(character)
            if (value < 0) return null

            buffer = (buffer shl 6) or value
            bitsCollected += 6
            if (bitsCollected >= 8) {
                bitsCollected -= 8
                output.write((buffer shr bitsCollected) and 0xFF)
            }
        }

        return output.toByteArray()
    }

    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * Compares two strings in time independent of how many leading characters match,
     * so a caller cannot recover a credential byte-by-byte by timing responses.
     *
     * Length still leaks, which is unavoidable and not sensitive here.
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        if (aBytes.size != bBytes.size) return false
        var diff = 0
        for (i in aBytes.indices) {
            diff = diff or (aBytes[i].toInt() xor bBytes[i].toInt())
        }
        return diff == 0
    }

    /**
     * Whether a request presenting [header] may proceed.
     *
     * When [authEnabled] is false the server is open by design (the user opted out).
     * When it is true but no credentials are configured, the server stays open too --
     * refusing every request would lock the user out of their own camera with no way
     * back in. [CctvServerService] avoids that state by seeding a generated password.
     */
    fun isAuthorized(
        authEnabled: Boolean,
        expectedUsername: String,
        expectedPassword: String,
        header: String?
    ): Boolean {
        if (!authEnabled) return true
        if (expectedUsername.isEmpty() || expectedPassword.isEmpty()) return true

        val credentials = parseBasicHeader(header) ?: return false
        // Both comparisons always run -- no short-circuit, so timing does not reveal
        // whether it was the username or the password that was wrong.
        val userOk = constantTimeEquals(credentials.first, expectedUsername)
        val passOk = constantTimeEquals(credentials.second, expectedPassword)
        return userOk and passOk
    }

    /** Name of the persistent "stay logged in" cookie issued after a successful Basic-Auth request. */
    const val SESSION_COOKIE_NAME = "cctv_session"

    /** ~10 years -- there is no server-side session store to expire, so this is effectively permanent. */
    private const val SESSION_COOKIE_MAX_AGE_SECONDS = 315360000L

    /**
     * Derives a bearer token from the *current* configured credentials rather than issuing and
     * storing a random one server-side: changing the dashboard username/password immediately
     * invalidates every previously issued cookie (the same security property [isAuthorized]
     * already has) without this app needing any session store, expiry sweep, or persistence
     * across restarts. One-way (SHA-256) so a cookie captured by sniffing this plaintext,
     * no-TLS, LAN-only traffic hands an eavesdropper a replayable credential, same as today's
     * Basic header does, but not the literal password (unlike that header's base64, which is
     * trivially reversible).
     */
    fun computeSessionToken(username: String, password: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest("v1:$username:$password".toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(hash.size * 2)
        for (byte in hash) {
            builder.append("0123456789abcdef"[(byte.toInt() shr 4) and 0xF])
            builder.append("0123456789abcdef"[byte.toInt() and 0xF])
        }
        return builder.toString()
    }

    /**
     * Whether [sessionCookieValue] matches the token for the currently configured credentials.
     * Mirrors [isAuthorized]'s fail-open rules (auth disabled, or no credentials configured yet)
     * so callers can OR the two checks together without either one being more permissive than
     * the other.
     */
    fun isSessionAuthorized(
        authEnabled: Boolean,
        expectedUsername: String,
        expectedPassword: String,
        sessionCookieValue: String?
    ): Boolean {
        if (!authEnabled) return true
        if (expectedUsername.isEmpty() || expectedPassword.isEmpty()) return true
        if (sessionCookieValue == null) return false
        return constantTimeEquals(sessionCookieValue, computeSessionToken(expectedUsername, expectedPassword))
    }

    /**
     * The `Set-Cookie` header value to hand a client that just authenticated via Basic Auth, so
     * its browser never has to show that login prompt again until the password changes. No
     * `Secure` attribute -- this server is plain HTTP only. Null when there are no credentials
     * configured yet to key the cookie to.
     */
    fun sessionCookieHeader(username: String, password: String): String? {
        if (username.isEmpty() || password.isEmpty()) return null
        val token = computeSessionToken(username, password)
        return "$SESSION_COOKIE_NAME=$token; Max-Age=$SESSION_COOKIE_MAX_AGE_SECONDS; Path=/; HttpOnly; SameSite=Strict"
    }

    /**
     * Rejects cross-site requests. A browser attaches `Origin` on any cross-origin
     * request, so a page on the public internet cannot drive this server even while
     * the victim's browser holds valid credentials (classic CSRF against a LAN device).
     *
     * A missing Origin means a non-browser client (curl, an NVR, a script) -- allowed.
     */
    fun isOriginAllowed(originHeader: String?, hostHeader: String?): Boolean {
        if (originHeader.isNullOrEmpty() || originHeader == "null") return true
        val originHost = hostFromOrigin(originHeader) ?: return false
        val requestHost = hostHeader?.substringBefore(':')?.lowercase() ?: return false
        return originHost == requestHost
    }

    private fun hostFromOrigin(origin: String): String? {
        val withoutScheme = origin.substringAfter("://", missingDelimiterValue = "")
        if (withoutScheme.isEmpty()) return null
        return withoutScheme.substringBefore('/').substringBefore(':').lowercase()
    }

    /** Generates a credential using [java.security.SecureRandom]. */
    fun generatePassword(length: Int = 16): String {
        require(length > 0) { "length must be positive" }
        val random = java.security.SecureRandom()
        val builder = StringBuilder(length)
        repeat(length) {
            builder.append(PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)])
        }
        return builder.toString()
    }

    /**
     * Escapes text for interpolation into HTML. Without this, the username and password
     * -- both settable over the network -- are injected raw into the dashboard markup.
     */
    fun escapeHtml(value: String): String {
        val builder = StringBuilder(value.length)
        for (character in value) {
            when (character) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&#39;")
                else -> builder.append(character)
            }
        }
        return builder.toString()
    }
}
