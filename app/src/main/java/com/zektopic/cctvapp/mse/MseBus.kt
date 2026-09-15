package com.zektopic.cctvapp.mse

import java.util.concurrent.atomic.AtomicReference

/**
 * The live [MseVideoBridge], if any -- published by
 * `CctvServerService` (`.service`) once it exists, cleared in that service's `onDestroy()`.
 * `WebServer`'s WebSocket handler (`.web`/`.mse`) reads through this to attach each new viewer,
 * without either side referencing the other's concrete class -- the same cross-package pattern
 * the old `WebRtcBus` (and, before it, `CameraRuntimeBus`) used for the same purpose.
 */
object MseBus {
    val bridge = AtomicReference<MseVideoBridge?>(null)
}
