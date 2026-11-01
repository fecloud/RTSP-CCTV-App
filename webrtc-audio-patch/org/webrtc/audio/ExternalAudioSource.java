/*
 * Not part of upstream WebRTC -- added by this app's build so WebRtcAudioRecord.java (also in
 * this directory) can pull PCM from somewhere other than android.media.AudioRecord without
 * depending on any app-specific class. Keeping this interface app-agnostic is what lets
 * compileWebRtcAudioPatch (see app/build.gradle.kts) build without a circular dependency on the
 * app module: the patched WebRtcAudioRecord only needs this interface to exist, and the app wires
 * a concrete implementation in at runtime via WebRtcAudioRecord.setExternalAudioSource (see
 * com.zektopic.cctvapp.webrtc.WebRtcMicFeed).
 */
package org.webrtc.audio;

public interface ExternalAudioSource {
  /**
   * Must always return an array of exactly {@code chunkBytes} length -- never null, never a
   * different size. If no real data is available within {@code timeoutMs}, return silence
   * (all-zero bytes) instead of blocking longer or throwing; WebRtcAudioRecord's capture thread
   * calls this in a tight loop and a stuck call here would stall stopRecording()'s thread join.
   */
  byte[] pollChunk(int chunkBytes, int timeoutMs);
}
