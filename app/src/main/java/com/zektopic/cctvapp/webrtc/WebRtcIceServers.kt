package com.zektopic.cctvapp.webrtc

import org.webrtc.PeerConnection

/**
 * Resolves the WebRTC preview's effective ICE server list: the user's statically configured
 * STUN/TURN servers (the `/ice` page, [IceServerConfig]) plus -- when dynamic TURN is enabled -- a
 * short-lived credential fetched from a signaling server by [DynamicTurnProvider].
 *
 * Both ends of a connection resolve through here so they always agree: the browser via
 * `/ice-servers.json` ([resolveJson]) and the phone via [WebRtcPreviewBridge.attachViewer]
 * ([resolvePeerConnectionIceServers]). Takes primitives rather than importing `ServiceSettings`, so
 * the `.webrtc` package stays free of a dependency back on `.settings`.
 *
 * Blocking (the dynamic fetch is): only call from NanoHTTPD worker threads.
 */
object WebRtcIceServers {

    /** Always present in the ICE list, in every case -- a free public STUN to fall back on. */
    private const val MANDATORY_STUN_URL = "stun:stun.miwifi.com:3478"
    private val MANDATORY_STUN = IceServerConfig.Entry(
        urls = listOf(MANDATORY_STUN_URL), username = null, credential = null
    )

    private fun withMandatoryStun(entries: List<IceServerConfig.Entry>): List<IceServerConfig.Entry> =
        if (entries.any { e -> e.urls.any { it == MANDATORY_STUN_URL } }) entries
        else listOf(MANDATORY_STUN) + entries

    private fun resolveEntries(
        staticJson: String,
        dynamicTurnEnabled: Boolean,
        dynamicTurnUrl: String,
    ): List<IceServerConfig.Entry> {
        // When a credential is successfully fetched, use *only* it (plus the mandatory STUN) --
        // the statically configured TURN list is deliberately dropped. If the fetch fails, or
        // dynamic TURN is off, fall back to the static list. Either way the mandatory STUN stays.
        if (dynamicTurnEnabled && dynamicTurnUrl.isNotBlank()) {
            DynamicTurnProvider.getTurnEntry(dynamicTurnUrl)?.let { dynamic ->
                return listOf(MANDATORY_STUN, dynamic)
            }
        }
        return withMandatoryStun(IceServerConfig.parseOrDefault(staticJson))
    }

    /** The merged list as `RTCIceServer[]` JSON, for the browser. */
    fun resolveJson(staticJson: String, dynamicTurnEnabled: Boolean, dynamicTurnUrl: String): String =
        IceServerConfig.entriesToJson(resolveEntries(staticJson, dynamicTurnEnabled, dynamicTurnUrl))

    /** The merged list as WebRTC [PeerConnection.IceServer]s, for the phone's peer connection. */
    fun resolvePeerConnectionIceServers(
        staticJson: String,
        dynamicTurnEnabled: Boolean,
        dynamicTurnUrl: String,
    ): List<PeerConnection.IceServer> =
        IceServerConfig.entriesToPeerConnectionIceServers(
            resolveEntries(staticJson, dynamicTurnEnabled, dynamicTurnUrl)
        )
}
