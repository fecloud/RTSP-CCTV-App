package com.zektopic.cctvapp.webrtc

import java.util.concurrent.atomic.AtomicReference

/**
 * The live [WebRtcVideoBridge], if any -- published by
 * `CctvServerService` (`.service`) once it exists, cleared in that service's `onDestroy()`.
 * `WebServer`'s WebSocket signaling handler (`.web`/`.webrtc`) reads through this to create
 * a [org.webrtc.PeerConnection] for each new viewer, without either side referencing the
 * other's concrete class -- the same cross-package pattern the old `CameraRuntimeBus` used
 * for the JPEG snapshot feed.
 */
object WebRtcBus {
    val bridge = AtomicReference<WebRtcVideoBridge?>(null)
}
