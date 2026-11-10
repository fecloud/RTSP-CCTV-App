package com.zektopic.cctvapp.webrtc

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.PeerConnection

/**
 * The WebRTC preview's STUN/TURN servers, stored as a JSON array in the browser's own
 * `RTCIceServer[]` shape (`[{"urls": [...], "username": "...", "credential": "..."}]`) -- so the
 * exact same string is handed to the dashboard's `RTCPeerConnection` (via `/ice-servers.json`, see
 * `webrtc-preview.js`) and parsed here into [PeerConnection.IceServer]s for the phone's side, and
 * both ends of a connection always agree on which servers to use.
 *
 * Edited from the dashboard's `/ice` page; persisted as
 * [com.zektopic.cctvapp.settings.ServiceSettings.webrtcIceServersJson]. Only read when a new
 * viewer connects, so a change applies to the next (re)connection rather than any live one.
 */
object IceServerConfig {

    val DEFAULT_JSON: String = """
        [
          {
            "urls": ["stun:stun.miwifi.com:3478"]
          },
          {
            "urls": [
              "turn:turn.cloudflare.com:3478?transport=udp",
              "turn:turn.cloudflare.com:3478?transport=tcp",
              "turns:turn.cloudflare.com:5349?transport=tcp",
              "turn:turn.cloudflare.com:443?transport=udp",
              "turn:turn.cloudflare.com:80?transport=tcp",
              "turns:turn.cloudflare.com:443?transport=tcp"
            ],
            "username": "g0668bec9fb84f28df2cd96daf4476cc8ad25b4b1e06499c580fa28952ad3558",
            "credential": "d2f3dfcd7f5112ac104960c99be053928d142b5aeeec23be36a6210472b16b18"
          }
        ]
    """.trimIndent()

    private val URL_SCHEMES = listOf("stun:", "stuns:", "turn:", "turns:")

    /** One parsed entry, Android-API-free so validation doesn't depend on `org.webrtc`. */
    data class Entry(val urls: List<String>, val username: String?, val credential: String?)

    /**
     * Parses and validates [json]; returns the entries, or throws [IllegalArgumentException]
     * with a message suitable for showing straight back to the user on the `/ice` page.
     */
    fun parse(json: String): List<Entry> {
        val array = try {
            JSONArray(json.trim())
        } catch (e: JSONException) {
            throw IllegalArgumentException("Not a JSON array: ${e.message}")
        }
        return (0 until array.length()).map { i ->
            val obj = array.optJSONObject(i)
                ?: throw IllegalArgumentException("Entry #${i + 1} is not an object")
            val urls = readUrls(obj, i)
            val username = obj.optStringOrNull("username")
            val credential = obj.optStringOrNull("credential")
            if (urls.any { it.startsWith("turn") } && (username == null || credential == null)) {
                throw IllegalArgumentException("Entry #${i + 1}: TURN servers need both \"username\" and \"credential\"")
            }
            Entry(urls, username, credential)
        }
    }

    /** Never throws: an invalid stored value (shouldn't happen -- writes are validated) falls back to [DEFAULT_JSON]. */
    fun toPeerConnectionIceServers(json: String): List<PeerConnection.IceServer> {
        val entries = runCatching { parse(json) }.getOrElse { parse(DEFAULT_JSON) }
        return entries.map { entry ->
            PeerConnection.IceServer.builder(entry.urls).apply {
                entry.username?.let { setUsername(it) }
                entry.credential?.let { setPassword(it) }
            }.createIceServer()
        }
    }

    private fun readUrls(obj: JSONObject, index: Int): List<String> {
        val raw = obj.opt("urls")
        val urls = when (raw) {
            is String -> listOf(raw)
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            else -> throw IllegalArgumentException("Entry #${index + 1} is missing \"urls\"")
        }.map { it.trim() }
        if (urls.isEmpty() || urls.any { it.isEmpty() }) {
            throw IllegalArgumentException("Entry #${index + 1}: \"urls\" must not be empty")
        }
        urls.firstOrNull { url -> URL_SCHEMES.none { url.startsWith(it) } }?.let {
            throw IllegalArgumentException("Entry #${index + 1}: \"$it\" must start with stun:, stuns:, turn: or turns:")
        }
        return urls
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
