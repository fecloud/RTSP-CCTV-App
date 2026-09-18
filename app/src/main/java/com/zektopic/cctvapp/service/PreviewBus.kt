package com.zektopic.cctvapp.service

import com.zektopic.cctvapp.mse.MseVideoBridge
import com.zektopic.cctvapp.webrtc.WebRtcPreviewBridge
import java.util.concurrent.atomic.AtomicReference

/**
 * The live [MseVideoBridge]/[WebRtcPreviewBridge], if any -- published by [CctvServerService]
 * once each exists, cleared in that service's `onDestroy()`. `WebServer`'s WebSocket handlers
 * (`.web`/`.mse`/`.webrtc`) read through this to attach each new viewer, without those packages
 * referencing `CctvServerService` (or each other's bridge-owning package) directly -- the same
 * cross-package pattern the old, now-merged `MseBus`/`WebRtcBus` (and, before them,
 * `CameraRuntimeBus`) used for the same purpose.
 */
object PreviewBus {
    val mseBridge = AtomicReference<MseVideoBridge?>(null)
    val webRtcBridge = AtomicReference<WebRtcPreviewBridge?>(null)
}
