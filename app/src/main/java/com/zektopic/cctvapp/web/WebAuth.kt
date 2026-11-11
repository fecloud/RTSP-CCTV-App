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
        return credentialsMatch(expectedUsername, expectedPassword, credentials.first, credentials.second)
    }

    /**
     * Checks a username/password pair (from the Basic header or the `/login` form) against the
     * configured credentials. Both comparisons always run -- no short-circuit, so timing does not
     * reveal whether it was the username or the password that was wrong. Callers handle the
     * fail-open cases (auth disabled / no credentials configured) themselves.
     */
    fun credentialsMatch(
        expectedUsername: String,
        expectedPassword: String,
        givenUsername: String,
        givenPassword: String
    ): Boolean {
        if (expectedUsername.isEmpty() || expectedPassword.isEmpty()) return false
        val userOk = constantTimeEquals(givenUsername, expectedUsername)
        val passOk = constantTimeEquals(givenPassword, expectedPassword)
        return userOk and passOk
    }

    /**
     * Name of the persistent "stay logged in" cookie issued by the `/login` page.
     *
     * `SameSite=Lax`, not `Strict`: opening the dashboard from a home-screen icon (installed PWA
     * or Chrome shortcut) reaches Chrome as an external VIEW intent, which Chrome treats as a
     * cross-site navigation (`Sec-Fetch-Site: cross-site`) and so withholds `Strict` cookies --
     * verified on-device, it made every launch land on `/login`. `Lax` cookies are still sent on
     * top-level GET navigations; the CSRF that would otherwise open up is closed by
     * [requiresPostWithSessionCookie] plus [isOriginAllowed].
     */
    const val SESSION_COOKIE_NAME = "cctv_session"

    /**
     * 400 days -- the longest lifetime Chrome (and the current cookie spec draft) will honor;
     * anything longer is silently clamped to it. The login is made permanent not by this number
     * but by re-issuing the cookie on every authorized page load (see `WebServer.serveHttp`), so
     * the 400-day window keeps sliding forward as long as the dashboard is opened at all within it.
     */
    private const val SESSION_COOKIE_MAX_AGE_SECONDS = 400L * 24 * 60 * 60

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
     * The `Set-Cookie` header value to hand a browser that just logged in (or is renewing an
     * existing login), so it stays logged in until the password changes. No `Secure` attribute
     * -- this server is plain HTTP only. Null when there are no credentials configured yet to
     * key the cookie to.
     */
    fun sessionCookieHeader(username: String, password: String): String? {
        if (username.isEmpty() || password.isEmpty()) return null
        val token = computeSessionToken(username, password)
        return "$SESSION_COOKIE_NAME=$token; Max-Age=$SESSION_COOKIE_MAX_AGE_SECONDS; Path=/; HttpOnly; SameSite=Lax"
    }

    /** The `Set-Cookie` header value that deletes the session cookie (used by `/logout`). */
    fun clearSessionCookieHeader(): String =
        "$SESSION_COOKIE_NAME=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax"

    /**
     * Where to send the browser after logging in: the `next` path it was bounced from, if that is
     * a plain same-origin path. Anything else (absolute URLs, protocol-relative `//host`,
     * backslash tricks, header-splitting characters, or `/login` itself) falls back to `/`, so
     * the login page can't be used as an open redirect.
     */
    fun safeRedirectTarget(next: String?): String {
        if (next.isNullOrEmpty() || !next.startsWith("/")) return "/"
        if (next.startsWith("//")) return "/"
        if (next.any { it == '\r' || it == '\n' || it == '\\' }) return "/"
        if (next == "/login" || next.startsWith("/login?")) return "/"
        return next
    }

    /**
     * Whether [uri] changes server state. With a `Lax` session cookie, a link on another site
     * would carry the cookie on a top-level GET, so cookie-authenticated requests to these paths
     * must be POSTs (cross-site POSTs carry an `Origin` that [isOriginAllowed] rejects, and `Lax`
     * withholds the cookie from them anyway). Basic-header clients (curl, NVRs) may keep using GET.
     */
    fun requiresPostWithSessionCookie(uri: String): Boolean =
        uri.startsWith("/action/") || uri.startsWith("/server/")

    /**
     * Whether an unauthenticated request is a browser navigating to a page (and so should be
     * redirected to `/login`) rather than an API/asset fetch or a non-browser client (which get
     * a plain 401 instead). `fetch()` sends a wildcard `Accept`, so it is never mistaken for a
     * page load.
     */
    fun isPageNavigation(method: String, acceptHeader: String?): Boolean =
        method.equals("GET", ignoreCase = true) && acceptHeader?.contains("text/html") == true

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
