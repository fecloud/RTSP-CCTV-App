/*
 *  Copyright (c) 2015 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

/*
 * MODIFIED by this app's build (see app/build.gradle.kts's compileWebRtcAudioPatch/patchWebRtcAar
 * tasks and webrtc-audio-patch/README context in the plan docs) -- based on
 * io.getstream:stream-webrtc-android:1.3.10's upstream source of this exact file (extracted from
 * its published -sources.jar). Every @CalledByNative method below keeps its original signature
 * unchanged, and this class keeps its original package/name, so the native .so bundled in that
 * same AAR binds to this replacement exactly as it would the original -- nothing on the native
 * side needs to change or be rebuilt.
 *
 * What changed and why: this app's target hardware's AudioPolicyManager only tolerates one
 * concurrent android.media.AudioRecord session, and RootEncoder's own MicrophoneSource already
 * holds it (see com.zektopic.cctvapp.webrtc.WebRtcMicFeed's kdoc). The original class opened its
 * own AudioRecord here and read real hardware samples from it in a loop. This version never opens
 * one at all: it still allocates the same cached direct ByteBuffer and calls the same two native
 * methods (nativeCacheDirectBufferAddress/nativeDataIsRecorded) exactly as before -- native only
 * cares that bytes land in that buffer before nativeDataIsRecorded is called, not where they came
 * from -- but the bytes themselves now come from pollChunk(), on an ExternalAudioSource set via
 * setExternalAudioSource() (that interface lives in this same package/directory, specifically so
 * this file has zero dependency on any com.zektopic.cctvapp class -- see ExternalAudioSource.java's
 * kdoc for why that avoids a build-order cycle).
 *
 * Everything tied to a *real* AudioRecord session is gone: no android.media.AudioRecord field, no
 * createAudioRecordOnMOrHigher/OnLowerThanM, no recording-configuration verification (there is no
 * session for AudioManager#getActiveRecordingConfigurations() to report on), no hardware AEC/NS
 * (WebRtcEngine.kt disables both via JavaAudioDeviceModule.Builder, since there is no
 * android.media.audiofx.AudioEffect session to attach one to anyway).
 *
 * AudioRecordThread.run() paces itself to a steady CALLBACK_BUFFER_SIZE_MS-per-call rhythm
 * explicitly (nextTickNs/parkNanos below) rather than just looping on pollNextChunk() as fast as
 * it returns -- confirmed on-device this isn't optional: a real android.media.AudioRecord.read()
 * call naturally blocks for ~10ms of real hardware capture time, which is what paced
 * nativeDataIsRecorded() correctly; without a real one, RootEncoder's mic callback (chunked to
 * its own buffer size, not a clean multiple of this class's 10ms callback size) could hand
 * WebRtcMicFeed's queue two 10ms chunks at once, and an unpaced loop drained both within
 * microseconds of each other and then sat idle for the next ~10ms -- audible as a "motorboating"
 * stutter, not a content/correctness bug (every individual chunk's PCM bytes were still correct).
 */
package org.webrtc.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaRecorder.AudioSource;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.webrtc.CalledByNative;
import org.webrtc.Logging;
import org.webrtc.ThreadUtils;
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback;
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStateCallback;
import org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback;
import java.nio.ByteBuffer;

// Widened from upstream's package-private to public: com.zektopic.cctvapp.webrtc.WebRtcMicFeed
// (a different package) needs to call setExternalAudioSource() below. Native resolves JNI calls
// by fully-qualified class name + method signature regardless of Java-level access modifiers, so
// this is safe -- it doesn't change anything the native .so cares about.
public class WebRtcAudioRecord {
  private static final String TAG = "WebRtcAudioRecordExternal";

  // Requested size of each recorded buffer provided to the client -- must match the chunk size
  // com.zektopic.cctvapp.webrtc.WebRtcMicFeed re-slices RootEncoder's mic PCM into.
  private static final int CALLBACK_BUFFER_SIZE_MS = 10;

  // Average number of callbacks per second.
  private static final int BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS;

  // The real-time cadence AudioRecordThread paces itself to -- see this file's header comment for
  // why this pacing exists at all (without it, bursty delivery from the ExternalAudioSource queue
  // produces audible stuttering even though every individual chunk's content is correct).
  private static final long CALLBACK_PERIOD_NS = CALLBACK_BUFFER_SIZE_MS * 1_000_000L;

  // Upper bound on how long pollNextChunk() will wait for a chunk before substituting silence --
  // kept under CALLBACK_PERIOD_NS so a starved poll still leaves the pacing loop below room to
  // land its next tick on schedule rather than always running late.
  private static final int EXTERNAL_SOURCE_POLL_TIMEOUT_MS = 8;

  private static final long AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS = 2000;

  public static final int DEFAULT_AUDIO_SOURCE = AudioSource.VOICE_COMMUNICATION;

  // Default audio data format is PCM 16 bit per sample.
  public static final int DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

  private static final int AUDIO_RECORD_START = 0;
  private static final int AUDIO_RECORD_STOP = 1;

  private final Context context;
  private final AudioManager audioManager;
  private final int audioSource;
  private final int audioFormat;

  private long nativeAudioRecord;

  private @Nullable ByteBuffer byteBuffer;

  /** True once initRecording() has allocated byteBuffer and cached its address with native -- replaces the original class's "audioRecord != null" readiness check. */
  private volatile boolean ready;

  private @Nullable AudioRecordThread audioThread;
  private @Nullable AudioDeviceInfo preferredDevice;

  private final ScheduledExecutorService executor;

  private volatile boolean microphoneMute;
  private byte[] emptyBytes;

  private int recordingSampleRate;
  private int recordingChannels;

  private final @Nullable AudioRecordErrorCallback errorCallback;
  private final @Nullable AudioRecordStateCallback stateCallback;
  private final @Nullable SamplesReadyCallback audioSamplesReadyCallback;
  private final boolean isAcousticEchoCancelerSupported;
  private final boolean isNoiseSuppressorSupported;

  /** See ExternalAudioSource.java's kdoc for why this lives here as a static rather than an instance field threaded in through a constructor JavaAudioDeviceModule.class doesn't know about. */
  private static volatile @Nullable ExternalAudioSource externalSource;

  public static void setExternalAudioSource(@Nullable ExternalAudioSource source) {
    externalSource = source;
  }

  /**
   * Pulls PCM from externalSource if one is wired up, otherwise (not wired yet, or none ever
   * will be -- e.g. no RECORD_AUDIO permission upstream) falls back to silence. Never blocks
   * longer than EXTERNAL_SOURCE_POLL_TIMEOUT_MS and never returns null/a wrong-length array,
   * regardless of what a misbehaving ExternalAudioSource does, since a bad array here would
   * corrupt byteBuffer.put() below.
   */
  private byte[] pollNextChunk(int chunkBytes) {
    ExternalAudioSource source = externalSource;
    if (source == null) {
      return emptyBytes;
    }
    byte[] chunk = source.pollChunk(chunkBytes, EXTERNAL_SOURCE_POLL_TIMEOUT_MS);
    if (chunk == null || chunk.length != chunkBytes) {
      return emptyBytes;
    }
    return chunk;
  }

  /**
   * Audio thread which keeps pulling PCM chunks (from pollNextChunk() instead of a real
   * AudioRecord.read(), see this file's header comment) and feeding them to the native
   * counterpart as a periodic sequence of callbacks using DataIsRecorded().
   */
  private class AudioRecordThread extends Thread {
    private volatile boolean keepAlive = true;

    public AudioRecordThread(String name) {
      super(name);
    }

    @Override
    public void run() {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
      Logging.d(TAG, "AudioRecordThread" + WebRtcAudioUtils.getThreadInfo());

      doAudioRecordStateCallback(AUDIO_RECORD_START);

      // nextTickNs is the sole pacing authority here -- see this file's header comment for why
      // just looping on pollNextChunk() as fast as it returns produces audible stuttering.
      long nextTickNs = System.nanoTime();
      while (keepAlive) {
        byte[] chunk = pollNextChunk(byteBuffer.capacity());
        byteBuffer.clear();
        byteBuffer.put(chunk);
        if (microphoneMute) {
          byteBuffer.clear();
          byteBuffer.put(emptyBytes);
        }
        // It's possible we've been shut down during the poll and stopRecording() tried and
        // failed to join this thread. To be a bit safer, try to avoid calling any native methods
        // in case they've been unregistered after stopRecording() returned.
        if (keepAlive) {
          nativeDataIsRecorded(nativeAudioRecord, byteBuffer.capacity(), System.nanoTime());
        }
        if (audioSamplesReadyCallback != null) {
          byte[] data = Arrays.copyOfRange(byteBuffer.array(), byteBuffer.arrayOffset(),
            byteBuffer.capacity() + byteBuffer.arrayOffset());
          audioSamplesReadyCallback.onWebRtcAudioRecordSamplesReady(
            new JavaAudioDeviceModule.AudioSamples(
              audioFormat, recordingChannels, recordingSampleRate, data));
        }

        nextTickNs += CALLBACK_PERIOD_NS;
        long sleepNs = nextTickNs - System.nanoTime();
        if (sleepNs > 0) {
          LockSupport.parkNanos(sleepNs);
        } else {
          // Fell behind (e.g. a GC pause, or pollNextChunk's poll alone ate the whole budget) --
          // resync to now rather than firing a burst of immediate catch-up ticks, which would
          // just recreate the exact burstiness this pacing exists to avoid.
          nextTickNs = System.nanoTime();
        }
      }

      doAudioRecordStateCallback(AUDIO_RECORD_STOP);
    }

    // Stops the inner thread loop. Does not block the calling thread.
    public void stopThread() {
      Logging.d(TAG, "stopThread");
      keepAlive = false;
    }
  }

  @CalledByNative
  WebRtcAudioRecord(Context context, AudioManager audioManager) {
    this(context, newDefaultScheduler() /* scheduler */, audioManager, DEFAULT_AUDIO_SOURCE,
      DEFAULT_AUDIO_FORMAT, null /* errorCallback */, null /* stateCallback */,
      null /* audioSamplesReadyCallback */, WebRtcAudioEffects.isAcousticEchoCancelerSupported(),
      WebRtcAudioEffects.isNoiseSuppressorSupported());
  }

  public WebRtcAudioRecord(Context context, ScheduledExecutorService scheduler,
                           AudioManager audioManager, int audioSource, int audioFormat,
                           @Nullable AudioRecordErrorCallback errorCallback,
                           @Nullable AudioRecordStateCallback stateCallback,
                           @Nullable SamplesReadyCallback audioSamplesReadyCallback,
                           boolean isAcousticEchoCancelerSupported, boolean isNoiseSuppressorSupported) {
    if (isAcousticEchoCancelerSupported && !WebRtcAudioEffects.isAcousticEchoCancelerSupported()) {
      throw new IllegalArgumentException("HW AEC not supported");
    }
    if (isNoiseSuppressorSupported && !WebRtcAudioEffects.isNoiseSuppressorSupported()) {
      throw new IllegalArgumentException("HW NS not supported");
    }
    this.context = context;
    this.executor = scheduler;
    this.audioManager = audioManager;
    this.audioSource = audioSource;
    this.audioFormat = audioFormat;
    this.errorCallback = errorCallback;
    this.stateCallback = stateCallback;
    this.audioSamplesReadyCallback = audioSamplesReadyCallback;
    this.isAcousticEchoCancelerSupported = isAcousticEchoCancelerSupported;
    this.isNoiseSuppressorSupported = isNoiseSuppressorSupported;
    Logging.d(TAG, "ctor" + WebRtcAudioUtils.getThreadInfo());
  }

  @CalledByNative
  public void setNativeAudioRecord(long nativeAudioRecord) {
    this.nativeAudioRecord = nativeAudioRecord;
  }

  @CalledByNative
  boolean isAcousticEchoCancelerSupported() {
    return isAcousticEchoCancelerSupported;
  }

  @CalledByNative
  boolean isNoiseSuppressorSupported() {
    return isNoiseSuppressorSupported;
  }

  /** No real recording session ever exists to verify against -- this app's feed is always exactly what it claims to be, so these report "verified" unconditionally rather than reporting "unknown". */
  @CalledByNative
  boolean isAudioConfigVerified() {
    return true;
  }

  @CalledByNative
  boolean isAudioSourceMatchingRecordingSession() {
    return true;
  }

  /** No real AudioRecord session, so no android.media.audiofx.AudioEffect session to attach a hardware AEC to -- see this file's header comment; WebRtcEngine.kt also disables this via JavaAudioDeviceModule.Builder so native shouldn't be relying on it taking effect here anyway. */
  @CalledByNative
  private boolean enableBuiltInAEC(boolean enable) {
    Logging.d(TAG, "enableBuiltInAEC(" + enable + ") -- no-op, no real AudioRecord session");
    return false;
  }

  /** See enableBuiltInAEC's kdoc -- same reasoning for noise suppression. */
  @CalledByNative
  private boolean enableBuiltInNS(boolean enable) {
    Logging.d(TAG, "enableBuiltInNS(" + enable + ") -- no-op, no real AudioRecord session");
    return false;
  }

  @CalledByNative
  private int initRecording(int sampleRate, int channels) {
    Logging.d(TAG, "initRecording(sampleRate=" + sampleRate + ", channels=" + channels + ")");
    if (ready) {
      reportWebRtcAudioRecordInitError("InitRecording called twice without StopRecording.");
      return -1;
    }
    recordingSampleRate = sampleRate;
    recordingChannels = channels;
    final int bytesPerFrame = channels * getBytesPerSample(audioFormat);
    final int framesPerBuffer = sampleRate / BUFFERS_PER_SECOND;
    byteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer);
    if (!(byteBuffer.hasArray())) {
      reportWebRtcAudioRecordInitError("ByteBuffer does not have backing array.");
      byteBuffer = null;
      return -1;
    }
    Logging.d(TAG, "byteBuffer.capacity: " + byteBuffer.capacity());
    emptyBytes = new byte[byteBuffer.capacity()];
    // Rather than passing the ByteBuffer with every callback (requiring the potentially
    // expensive GetDirectBufferAddress) we simply have the native class cache the address to the
    // memory once.
    nativeCacheDirectBufferAddress(nativeAudioRecord, byteBuffer);
    ready = true;
    return framesPerBuffer;
  }

  /** Not called by native (not @CalledByNative) and never called by this app (WebRtcEngine.kt never calls JavaAudioDeviceModule.setPreferredInputDevice) -- kept only so JavaAudioDeviceModule.class's compiled call site still resolves. No-op: there's no real AudioRecord to route. */
  @RequiresApi(android.os.Build.VERSION_CODES.M)
  void setPreferredDevice(@Nullable AudioDeviceInfo preferredDevice) {
    this.preferredDevice = preferredDevice;
  }

  @CalledByNative
  private boolean startRecording() {
    Logging.d(TAG, "startRecording");
    assertTrue(ready);
    assertTrue(audioThread == null);
    audioThread = new AudioRecordThread("AudioRecordJavaThread");
    audioThread.start();
    return true;
  }

  @CalledByNative
  private boolean stopRecording() {
    Logging.d(TAG, "stopRecording");
    assertTrue(audioThread != null);
    audioThread.stopThread();
    if (!ThreadUtils.joinUninterruptibly(audioThread, AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS)) {
      Logging.e(TAG, "Join of AudioRecordJavaThread timed out");
    }
    audioThread = null;
    byteBuffer = null;
    ready = false;
    return true;
  }

  // Helper method which throws an exception when an assertion has failed.
  private static void assertTrue(boolean condition) {
    if (!condition) {
      throw new AssertionError("Expected condition to be true");
    }
  }

  private native void nativeCacheDirectBufferAddress(
    long nativeAudioRecordJni, ByteBuffer byteBuffer);
  private native void nativeDataIsRecorded(
    long nativeAudioRecordJni, int bytes, long captureTimestampNs);

  // Sets all recorded samples to zero if `mute` is true, i.e., ensures that the microphone is
  // muted.
  public void setMicrophoneMute(boolean mute) {
    Logging.w(TAG, "setMicrophoneMute(" + mute + ")");
    microphoneMute = mute;
  }

  /** No real hardware NoiseSuppressor session to toggle -- see this file's header comment. Not called anywhere in this app today (kept only for JavaAudioDeviceModule.class's public API surface). */
  public boolean setNoiseSuppressorEnabled(boolean enabled) {
    Logging.w(TAG, "setNoiseSuppressorEnabled(" + enabled + ") -- no-op, no real AudioRecord session");
    return false;
  }

  private void reportWebRtcAudioRecordInitError(String errorMessage) {
    Logging.e(TAG, "Init recording error: " + errorMessage);
    if (errorCallback != null) {
      errorCallback.onWebRtcAudioRecordInitError(errorMessage);
    }
  }

  private void doAudioRecordStateCallback(int audioState) {
    Logging.d(TAG, "doAudioRecordStateCallback: " + audioStateToString(audioState));
    if (stateCallback != null) {
      if (audioState == WebRtcAudioRecord.AUDIO_RECORD_START) {
        stateCallback.onWebRtcAudioRecordStart();
      } else if (audioState == WebRtcAudioRecord.AUDIO_RECORD_STOP) {
        stateCallback.onWebRtcAudioRecordStop();
      } else {
        Logging.e(TAG, "Invalid audio state");
      }
    }
  }

  // Reference from Android code, AudioFormat.getBytesPerSample. BitPerSample / 8
  private static int getBytesPerSample(int audioFormat) {
    switch (audioFormat) {
      case AudioFormat.ENCODING_PCM_8BIT:
        return 1;
      case AudioFormat.ENCODING_PCM_16BIT:
      case AudioFormat.ENCODING_IEC61937:
      case AudioFormat.ENCODING_DEFAULT:
        return 2;
      case AudioFormat.ENCODING_PCM_FLOAT:
        return 4;
      case AudioFormat.ENCODING_INVALID:
      default:
        throw new IllegalArgumentException("Bad audio format " + audioFormat);
    }
  }

  private static String audioStateToString(int state) {
    switch (state) {
      case WebRtcAudioRecord.AUDIO_RECORD_START:
        return "START";
      case WebRtcAudioRecord.AUDIO_RECORD_STOP:
        return "STOP";
      default:
        return "INVALID";
    }
  }

  private static final AtomicInteger nextSchedulerId = new AtomicInteger(0);

  static ScheduledExecutorService newDefaultScheduler() {
    AtomicInteger nextThreadId = new AtomicInteger(0);
    return Executors.newScheduledThreadPool(0, new ThreadFactory() {
      @Override
      public Thread newThread(Runnable r) {
        Thread thread = Executors.defaultThreadFactory().newThread(r);
        thread.setName(String.format("WebRtcAudioRecordScheduler-%s-%s",
          nextSchedulerId.getAndIncrement(), nextThreadId.getAndIncrement()));
        return thread;
      }
    });
  }
}
