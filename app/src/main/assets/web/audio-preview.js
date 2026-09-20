// --- Shared audio preview -- independent of which video preview (MSE or WebRTC) is active ---
// Wire format over /audio-ws (see AudioStreamSocket.kt): one text control message
// (sampleRate/channelCount), then binary messages each holding one raw 16-bit PCM chunk straight
// off the mic -- no encoding, no server-side muxing, no tag byte (audio is the only frame kind on
// this socket). Runs unconditionally regardless of CCTV_PREVIEW_TYPE -- see AudioStreamBridge.kt's
// kdoc for why this is independent of which video transport is active.
//
// Playback schedules each chunk directly via the Web Audio API (AudioBufferSourceNode.start(time))
// instead of going through MediaSource/SourceBuffer fed AAC (tried first, reverted: MSE's own
// playback-start buffering is browser-controlled, and without something to periodically hard-seek
// back to the live edge to correct for it -- which used to come for free by riding along inside
// the video preview's own self-healing before audio was split into its own MediaSource -- latency
// just sits at whatever that initial buffer was, indefinitely) or the WebCodecs `AudioDecoder` API
// (tried before that, also AAC, reverted: unavailable in some browsers, e.g. Firefox).
//
// Raw PCM (not AAC) is what makes decode-free direct scheduling possible without any codec API at
// all -- 8x the bandwidth of AAC at this bitrate (~256kbps vs ~32kbps), which is trivial next to
// the video already on the same LAN connection. Scheduling each chunk at
// `max(nextPlayTime, audioCtx.currentTime)` is inherently self-correcting: a network hiccup can
// never leave it playing further and further behind the way MSE's buffered approach could --
// worst case is a brief gap while catching back up to "now".
const AUDIO_PREVIEW = {
    RECONNECT_DELAY_MS: 2000,
    // localStorage key for the mute preference -- see restoreMutePreference()'s kdoc for why
    // this needs to survive across the reload dashboard.js's preview-type switch does.
    MUTE_STORAGE_KEY: 'cctv-audio-muted',
};

let audioCtx = null;
let gainNode = null;
let nextPlayTime = 0;
let sampleRate = null;

/**
 * Switching between the MSE and WebRTC video previews (dashboard.js's preview-type chip) is a
 * full `location.reload()` -- audio-preview.js re-runs from scratch either way, since there's no
 * in-page teardown hook to hand its live WebSocket/AudioContext off across that reload. Without
 * this, every switch would silently revert to muted (the default below) even though the user had
 * just unmuted it, forcing a re-click after every single mode switch. Mirrors how dashboard.html
 * itself persists `cctv-preview-type` across the very same reload.
 */
function restoreMutePreference() {
    return localStorage.getItem(AUDIO_PREVIEW.MUTE_STORAGE_KEY) !== 'false';
}

function saveMutePreference(muted) {
    localStorage.setItem(AUDIO_PREVIEW.MUTE_STORAGE_KEY, String(muted));
}

function updateAudioMuteChip(muted) {
    const chip = document.getElementById('chipAudioMute');
    if (!chip) return;
    chip.textContent = muted ? '🔇' : '🔊';
    chip.title = muted ? 'Tap to unmute audio' : 'Tap to mute audio';
}

/** If gain is meant to be non-zero (unmuted) but the context never actually got running, falls back to muted so the chip stays truthful instead of showing 🔊 while nothing is actually audible -- see ensureAudioGraph()'s kdoc. */
function fallbackToMutedIfNotRunning() {
    if (!gainNode || gainNode.gain.value === 0) return;
    if (audioCtx.state === 'running') return;
    gainNode.gain.value = 0;
    saveMutePreference(true);
    updateAudioMuteChip(true);
}

/**
 * Lazily creates the audio graph once, on first use (either the control message arriving or the
 * user clicking the mute chip, whichever comes first). Restores the persisted mute preference
 * immediately rather than always starting muted, so a mode switch that reloads the page doesn't
 * also reset a preference the user had just set -- see restoreMutePreference()'s kdoc.
 */
function ensureAudioGraph() {
    if (audioCtx) return;
    audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    gainNode = audioCtx.createGain();
    gainNode.gain.value = restoreMutePreference() ? 0 : 1;
    gainNode.connect(audioCtx.destination);
    if (gainNode.gain.value > 0) {
        // Browsers block audio output without a fresh user gesture -- a reload (even one
        // triggered by the user's own click on the preview-type chip) doesn't carry a gesture
        // across the navigation, so this can silently fail to actually start the context.
        // resume()'s own promise isn't a reliable signal for that: confirmed on-device that at
        // least one browser leaves it permanently pending (neither resolving nor rejecting)
        // instead of settling either way when blocked, so .then()/.catch() here would just never
        // fire and the chip would keep claiming "unmuted" forever with nothing actually audible.
        // Checking .state directly after a tick catches that regardless of what the promise does.
        audioCtx.resume();
        setTimeout(fallbackToMutedIfNotRunning, 250);
    }
    updateAudioMuteChip(gainNode.gain.value === 0);
}

/** Wired to the dashboard's audio-mute chip (see dashboard.html). */
function toggleAudioMute() {
    ensureAudioGraph();
    if (audioCtx.state === 'suspended') audioCtx.resume();
    const wasMuted = gainNode.gain.value === 0;
    gainNode.gain.value = wasMuted ? 1 : 0;
    saveMutePreference(!wasMuted);
    updateAudioMuteChip(!wasMuted);
}

/**
 * Schedules one PCM chunk back-to-back after whatever's already queued -- the standard technique
 * for gapless playback of a chunked PCM stream. `nextPlayTime` is clamped up to "now" so a
 * network stall can never leave audio playing further and further behind (see this file's header
 * comment).
 */
function scheduleAudioChunk(int16Samples) {
    const frameCount = int16Samples.length;
    const buffer = audioCtx.createBuffer(1, frameCount, sampleRate);
    const channelData = buffer.getChannelData(0);
    for (let i = 0; i < frameCount; i++) {
        channelData[i] = int16Samples[i] / 32768;
    }

    const source = audioCtx.createBufferSource();
    source.buffer = buffer;
    source.connect(gainNode);

    const startAt = Math.max(nextPlayTime, audioCtx.currentTime);
    source.start(startAt);
    nextPlayTime = startAt + buffer.duration;
}

function startAudioPreview() {
    nextPlayTime = 0;

    const wsProtocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(wsProtocol + '//' + location.host + '/audio-ws');
    ws.binaryType = 'arraybuffer';

    ws.onmessage = (event) => {
        if (typeof event.data === 'string') {
            const config = JSON.parse(event.data);
            sampleRate = config.sampleRate;
            ensureAudioGraph();
            return;
        }
        if (!audioCtx) return;
        scheduleAudioChunk(new Int16Array(event.data));
    };
    // Reconnects on close for any reason: no audio available yet, or a dropped connection.
    ws.onclose = () => setTimeout(startAudioPreview, AUDIO_PREVIEW.RECONNECT_DELAY_MS);
    ws.onerror = () => ws.close();
}

startAudioPreview();
