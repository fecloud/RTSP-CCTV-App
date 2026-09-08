package com.zektopic.cctvapp.service

import com.zektopic.cctvapp.settings.ServiceStateRepository
import java.util.concurrent.atomic.AtomicReference

/**
 * The live camera state that genuinely can't go through [ServiceStateRepository]: a
 * `ByteArray` field there would break its `StateFlow` no-op conflation (`ByteArray`
 * uses reference equality, so every settings emission would look "changed").
 * [CctvServerService] writes into this; `CctvApplication`'s `WebServer` reads through
 * it as a plain utility -- neither side references the other's concrete class.
 */
object CameraRuntimeBus {
    val currentSnapshot = AtomicReference<ByteArray?>(null)

    /** Last time a dashboard client asked for /shot.jpg, for idle throttling. */
    @Volatile var lastSnapshotRequestMs = 0L
}
