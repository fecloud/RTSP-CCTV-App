// --- MSE live preview ---
// Wire format over /ws (see MseStreamSocket.kt): one text control message with the
// stream's fps (and sampleRate/channelCount/audioConfig if a microphone is present), then
// binary messages each prefixed with a 1-byte tag (0=video NAL, 1=AAC frame) straight off
// the encoders -- no server-side muxing. `h264-converter.js` (vendored, same library
// ws-scrcpy's web client uses) builds the video fmp4 fragments and manages its own
// MediaSource/SourceBuffer; there's no equivalent library for audio, so AudioTrackAppender
// (vendored separately, /audio-muxer.js) is our own small muxer that adds a second
// SourceBuffer to that same MediaSource.
//
// The self-healing below (GOP-based buffer trimming, per-frame stall detection) is a
// direct port of ws-scrcpy's own web client (src/app/player/MsePlayer.ts /
// BasePlayer.ts, NetrisTV/ws-scrcpy) rather than something designed from scratch --
// function/variable names deliberately match it so the two stay easy to compare.
const MSE_PREVIEW = {
    RECONNECT_DELAY_MS: 2000,
    // Forces a reconnect if no bytes arrive for this long (the WS can go silently dead).
    WATCHDOG_INTERVAL_MS: 3000,
    STALL_TIMEOUT_MS: 8000,
    HEARTBEAT_LOG_INTERVAL_MS: 2000,
};

// h264-converter.js otherwise swallows its own internal errors (e.g. appendBuffer
// throwing QuotaExceededError) silently -- surface just those, not its very verbose
// per-fragment debug logging (which would fire dozens of times a second and drown out
// everything else). Global to the module, not per-connection, so only needs setting once.
if (typeof H264ConverterSetLogger === 'function') {
    H264ConverterSetLogger(() => {}, (msg) => console.error('[h264-converter]', msg));
}

// Backgrounded tabs get their timers throttled (and decoding paused) by the browser, so
// none of the self-healing below runs while hidden -- coming back after a long time away
// means a huge, stale backlog to crawl through rather than a clean catch-up. Force a
// fresh connection instead once the gap is more than trivial (a quick tab switch
// shouldn't pay a reconnect). `activeWs` is updated by startMsePreview() itself since
// `ws` is scoped per-connection, not visible from this top-level listener.
let activeWs = null;
let pageHiddenSince = -1;
document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
        pageHiddenSince = Date.now();
        return;
    }
    if (pageHiddenSince === -1) return;
    const hiddenMs = Date.now() - pageHiddenSince;
    pageHiddenSince = -1;
    if (hiddenMs > 3000 && activeWs) activeWs.close();
});

function startMsePreview() {
    const video = document.getElementById('cam-preview');
    video.playbackRate = 1.0; // don't inherit a sped-up rate from a previous session

    let converter = null;
    let audioAppender = null;
    let watchdogTimer = null;
    let heartbeatTimer = null;
    let lastDataAt = Date.now();
    let ws = null;
    let messageCount = 0;
    let lastHeartbeatMessageCount = 0;

    // --- ws-scrcpy MsePlayer.ts port: GOP-based buffer trimming ---
    let blocks = [];               // {start, end}[], one per video keyframe seen
    let waitUntilSegmentRemoved = false;
    let pendingFrames = [];        // delta frames held back while a cleanup is in flight

    // --- ws-scrcpy MsePlayer.ts port: per-frame stall detection ---
    let videoStats = [];           // {timestamp, decodedFrames}[], rolling 1s window
    let inputTimestamps = [];      // one per appended video frame, rolling 1s window
    let noDecodedFramesSince = -1;
    let currentTimeNotChangedSince = -1;
    let aheadOfBufferSince = -1;
    let lastTime = -1;
    let seekingSince = -1;
    const MAX_TIME_TO_RECOVER = 200; // ms
    const MAX_AHEAD = -0.2;
    // ws-scrcpy also hard-seeks once buffered-ahead exceeds a per-browser MAX_BUFFER
    // (0.2/0.9/2s) -- reasonable for its own near-zero-latency local-device pipe, where
    // that drift is negligible. Ours naturally grows to a couple of seconds between
    // GOP-trims (WebSocket hop + h264-converter.js's own per-fragment append cadence).
    // Tried folding it into checkForBadState (fired a hard seek + ~1s rebuffer every few
    // seconds on healthy playback) and tried a separate soft playbackRate nudge instead
    // (confirmed on-device: changing playbackRate mid-decode itself provoked a genuine
    // decodedFrames stall on this device, which checkForBadState then hard-seeked for
    // anyway) -- both made things worse than doing nothing. The drift by itself hasn't
    // been observed to cause any actual problem: cleanSourceBuffer's GOP-based trim
    // still reclaims the old buffered range regardless of how far currentTime trails
    // the live edge, and this isn't a low-latency remote-control scenario where being a
    // couple of seconds behind live matters. Left alone entirely now.

    // Diagnostic-only: snapshots everything relevant to a stuck/frozen preview so a
    // console log capture is enough to diagnose it without reproducing on-device.
    function rangesOf(sb) {
        if (!sb) return 'none';
        const buffered = sb.buffered;
        const ranges = [];
        for (let i = 0; i < buffered.length; i++) ranges.push('[' + buffered.start(i).toFixed(2) + ',' + buffered.end(i).toFixed(2) + ']');
        return ranges.join(' ') || 'none';
    }
    function snapshot() {
        const quality = typeof video.getVideoPlaybackQuality === 'function' ? video.getVideoPlaybackQuality() : null;
        const sb = converter ? converter.sourceBuffer : null;
        const audioSb = audioAppender ? audioAppender.sourceBuffer : null;
        return {
            currentTime: video.currentTime.toFixed(2),
            paused: video.paused,
            readyState: video.readyState,
            networkState: video.networkState,
            playbackRate: video.playbackRate,
            buffered: rangesOf(sb),
            audioBuffered: audioAppender ? rangesOf(audioSb) : null,
            sbUpdating: sb ? sb.updating : null,
            wsState: ws ? ws.readyState : null,
            decodedFrames: quality ? quality.totalVideoFrames : null,
            droppedFrames: quality ? quality.droppedVideoFrames : null,
        };
    }
    // A single flat string, not console.log(tag, {...}) -- DevTools truncates an inline
    // object preview with '...' past a handful of properties, which silently drops the
    // fields (decodedFrames especially) a copy-pasted report needs most.
    function log(tag, extra) {
        const s = snapshot();
        let line = '[MSE] ' + tag + ' t=' + s.currentTime + ' paused=' + s.paused + ' ready=' + s.readyState +
            ' net=' + s.networkState + ' rate=' + s.playbackRate + ' buf=' + s.buffered +
            (s.audioBuffered !== null ? ' audioBuf=' + s.audioBuffered : '') +
            ' sbUpd=' + s.sbUpdating + ' ws=' + s.wsState +
            ' decFrames=' + s.decodedFrames + ' dropFrames=' + s.droppedFrames;
        if (extra) line += ' ' + JSON.stringify(extra);
        console.log(line);
    }

    function onVideoWaiting() { log('video-waiting'); }
    function onVideoStalled() { log('video-stalled'); }
    function onVideoError() { log('video-error', { error: video.error ? video.error.code : null }); }
    video.addEventListener('waiting', onVideoWaiting);
    video.addEventListener('stalled', onVideoStalled);
    video.addEventListener('error', onVideoError);

    // Last 5 bits of the NAL header byte === 5: coded slice of an IDR picture (H.264
    // NAL unit type 5) -- see ITU-T H.264 Table 7-1. NALs are 4-byte-start-code-prefixed
    // (MseVideoBridge.kt normalizes this server-side), so byte 4 is that header byte.
    function isIFrame(frame) {
        return !!(frame && frame.length > 4 && (frame[4] & 31) === 5);
    }

    // Ports ws-scrcpy MsePlayer.cleanSourceBuffer(): once 10 GOPs have accumulated, drop
    // the oldest 5 in one remove() and replay whatever delta frames got held back while
    // that was in flight. Also trims the audio track to the same span, best-effort --
    // ws-scrcpy has no audio, so this part isn't in the reference.
    function cleanSourceBuffer() {
        const sb = converter && converter.sourceBuffer;
        if (!sb || sb.updating) return;
        if (blocks.length < 10) return;
        try {
            sb.removeEventListener('updateend', cleanSourceBuffer);
            waitUntilSegmentRemoved = false;
            const removeStart = blocks[0].start;
            const removeEnd = blocks[4].end;
            blocks = blocks.slice(5);
            sb.remove(removeStart, removeEnd);
            const audioSb = audioAppender && audioAppender.sourceBuffer;
            if (audioSb && !audioSb.updating && audioSb.buffered.length) {
                const audioStart = audioSb.buffered.start(0);
                const audioEnd = Math.min(removeEnd, audioSb.buffered.end(audioSb.buffered.length - 1));
                if (audioEnd > audioStart) audioSb.remove(audioStart, audioEnd);
            }
            let frame = pendingFrames.shift();
            while (frame) {
                if (!appendVideoFrame(frame)) {
                    pendingFrames.unshift(frame);
                    break;
                }
                frame = pendingFrames.shift();
            }
        } catch (e) {
            log('clean-source-buffer-failed', { error: String(e) });
        }
    }

    // Ports ws-scrcpy MsePlayer.checkForIFrame(): returns true once the frame has been
    // handed to the converter, false if the caller needs to hold onto it (queued in
    // pendingFrames) until cleanSourceBuffer() replays it.
    function appendVideoFrame(frame) {
        if (!converter) return false;
        const sb = converter.sourceBuffer;
        if (isIFrame(frame)) {
            let start = 0;
            let end = 0;
            if (video.buffered.length) {
                start = video.buffered.start(0);
                end = video.buffered.end(0);
            }
            if (end !== 0 && start < end) {
                blocks.push({ start, end });
                if (blocks.length > 10) {
                    waitUntilSegmentRemoved = true;
                    sb.addEventListener('updateend', cleanSourceBuffer);
                    converter.appendRawData(frame);
                    return true;
                }
            }
        }
        if (waitUntilSegmentRemoved) return false;
        converter.appendRawData(frame);
        return true;
    }

    function getPlaybackQualityStat() {
        if (typeof video.getVideoPlaybackQuality !== 'function') return null;
        const q = video.getVideoPlaybackQuality();
        return { timestamp: Date.now(), decodedFrames: q.totalVideoFrames };
    }

    // Ports ws-scrcpy MsePlayer.calculateMomentumStats(): decoded-frame delta over a
    // rolling 1-second window, so a genuine stall (decode flatlined) can be told apart
    // from just having decoded few frames because little time has passed.
    function calculateMomentumStats() {
        const stat = getPlaybackQualityStat();
        if (!stat) return null;
        const oneSecondBefore = stat.timestamp - 1000;
        videoStats.push(stat);
        while (videoStats.length && videoStats[0].timestamp < oneSecondBefore) videoStats.shift();
        while (inputTimestamps.length && inputTimestamps[0] < oneSecondBefore) inputTimestamps.shift();
        if (!videoStats.length) return null;
        const oldest = videoStats[0];
        return { decodedFrames: stat.decodedFrames - oldest.decodedFrames };
    }

    // Ports ws-scrcpy MsePlayer.checkForBadState(): called after every successfully
    // appended video frame (not on a timer -- this runs at the real frame rate), and
    // seeks to the live edge the moment any of its four conditions has held for
    // MAX_TIME_TO_RECOVER. Deliberately no escalation to a full reconnect after repeated
    // seeks (ws-scrcpy doesn't have one either) -- a straight port, not a hardened one.
    function checkForBadState() {
        const momentum = calculateMomentumStats();
        const now = Date.now();
        let hasReasonToJump = false;
        const reasons = []; // diagnostic-only, not in ws-scrcpy -- see the log() call below
        if (momentum) {
            if (momentum.decodedFrames === 0 && inputTimestamps.length > 0) {
                if (noDecodedFramesSince === -1) noDecodedFramesSince = now;
                else if (now - noDecodedFramesSince > MAX_TIME_TO_RECOVER) { hasReasonToJump = true; reasons.push('no-decoded-frames'); }
            } else {
                noDecodedFramesSince = -1;
            }
        }
        // Matches ws-scrcpy's exact condition here (not "if unchanged, start/keep a
        // timer") -- once currentTimeNotChangedSince is set, the next tick where
        // currentTime is STILL equal to lastTime no longer satisfies
        // `currentTimeNotChangedSince === -1`, so it falls through to the else branch
        // and gets reset. In practice this condition below almost never contributes to
        // hasReasonToJump on its own; kept bug-for-bug identical to the upstream source
        // rather than "fixed", per explicit instruction to match ws-scrcpy exactly.
        const currentTime = video.currentTime;
        if (currentTime === lastTime && currentTimeNotChangedSince === -1) {
            currentTimeNotChangedSince = now;
        } else {
            currentTimeNotChangedSince = -1;
        }
        lastTime = currentTime;

        if (!video.buffered.length) return;
        const end = video.buffered.end(0);
        const buffered = end - currentTime;
        if (buffered < MAX_AHEAD) {
            if (aheadOfBufferSince === -1) aheadOfBufferSince = now;
            else if (now - aheadOfBufferSince > MAX_TIME_TO_RECOVER) { hasReasonToJump = true; reasons.push('ahead-of-buffer'); }
        } else {
            aheadOfBufferSince = -1;
        }
        if (currentTimeNotChangedSince !== -1 && now - currentTimeNotChangedSince > MAX_TIME_TO_RECOVER) {
            hasReasonToJump = true;
            reasons.push('current-time-frozen');
        }
        if (!hasReasonToJump) return;
        if (seekingSince !== -1 && now - seekingSince < 1500) return;

        log('bad-state-seek', { liveEdge: end.toFixed(2), reasons: reasons, ahead: buffered.toFixed(2) });
        const onSeekEnd = () => {
            seekingSince = -1;
            video.removeEventListener('seeked', onSeekEnd);
            video.play().catch(() => {});
        };
        if (seekingSince !== -1) log('bad-state-seek-already-seeking');
        seekingSince = now;
        video.addEventListener('seeked', onSeekEnd);
        video.currentTime = end;
    }

    const wsProtocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(wsProtocol + '//' + location.host + '/ws');
    activeWs = ws;
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => log('ws-open');

    lastDataAt = Date.now();
    watchdogTimer = setInterval(() => {
        if (Date.now() - lastDataAt > MSE_PREVIEW.STALL_TIMEOUT_MS) {
            log('watchdog-stall');
            ws.close();
        }
    }, MSE_PREVIEW.WATCHDOG_INTERVAL_MS);

    ws.onmessage = (event) => {
        lastDataAt = Date.now();
        messageCount++;
        if (typeof event.data === 'string') {
            const msg = JSON.parse(event.data);
            // ws-scrcpy itself never batches (DEFAULT_FRAMES_PER_FRAGMENT=1) -- fine for
            // its own near-zero-latency local-device pipe. On-device testing here found
            // the opposite: appendBuffer() ~40 times/sec combined (video+audio, each its
            // own tiny fmp4 fragment) measurably correlated with more frequent decoder
            // stalls (checkForBadState's "no-decoded-frames" firing every 5-7s) than
            // batching a few frames per fragment. 5/3 confirmed on-device that batching
            // helps but added too much latency; 3/2 (~120-130ms/fragment, ~16
            // appendBuffer() calls/sec combined) is the current attempt at a smaller
            // latency cost while keeping most of the stability improvement.
            converter = new VideoConverter(video, msg.fps, /* framesPerFragment= */ 2);
            converter.play();
            if (msg.audioConfig) {
                audioAppender = new AudioTrackAppender(
                    converter.mediaSource, msg.sampleRate, msg.channelCount, new Uint8Array(msg.audioConfig),
                    /* framesPerFragment= */ 2);
            }
            log('fps', { fps: msg.fps, hasAudio: !!msg.audioConfig });
            heartbeatTimer = setInterval(() => {
                log('heartbeat', { msgsPerInterval: messageCount - lastHeartbeatMessageCount });
                lastHeartbeatMessageCount = messageCount;
            }, MSE_PREVIEW.HEARTBEAT_LOG_INTERVAL_MS);
            return;
        }
        // First byte is a tag (see MseStreamSocket.kt): 0 = video NAL, 1 = AAC frame.
        const tag = new Uint8Array(event.data, 0, 1)[0];
        const payload = new Uint8Array(event.data, 1);
        if (tag === 0) {
            inputTimestamps.push(Date.now());
            if (appendVideoFrame(payload)) {
                checkForBadState();
            } else {
                pendingFrames.push(payload);
            }
        } else if (tag === 1 && audioAppender) {
            audioAppender.appendRawFrame(payload);
        }
    };
    // Reconnects on close for any reason: not streaming yet, a codec/resolution/fps
    // restart, or the watchdog above forcing one.
    ws.onclose = (event) => {
        log('ws-close', { code: event.code, reason: event.reason, wasClean: event.wasClean });
        video.removeEventListener('waiting', onVideoWaiting);
        video.removeEventListener('stalled', onVideoStalled);
        video.removeEventListener('error', onVideoError);
        clearInterval(watchdogTimer);
        clearInterval(heartbeatTimer);
        setTimeout(startMsePreview, MSE_PREVIEW.RECONNECT_DELAY_MS);
    };
    ws.onerror = () => { log('ws-error'); ws.close(); };
}
if (typeof CCTV_PREVIEW_TYPE === 'undefined' || CCTV_PREVIEW_TYPE !== 'webrtc') {
    startMsePreview();
}
