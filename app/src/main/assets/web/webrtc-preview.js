// --- WebRTC live preview (opt-in via the dashboard's preview-type chip, see dashboard.js) ---
// Signaling over /webrtc-ws (see WebRtcSignalingSocket.kt): this side always initiates with an
// SDP offer built from recvonly video+audio transceivers, the phone answers with its shared
// camera track and its real WebRTC audio track (see WebRtcEngine.kt's kdoc for how that audio
// track gets its PCM without a second android.media.AudioRecord), and ICE candidates are relayed
// both ways as small JSON messages.
//
// Audio only exists here, in WebRTC preview mode -- MSE mode (mse-preview.js) has no audio at all.
// This used to be a separate, always-on preview (audio-preview.js, relaying raw PCM over its own
// /audio-ws WebSocket) specifically so audio didn't care which video transport was active; now
// that WebRTC carries a real audio track, riding it here is simpler, at the cost of MSE mode never
// having sound (an accepted tradeoff, see git history for the rationale.)
const WEBRTC_PREVIEW = {
    RECONNECT_DELAY_MS: 2000,
};

const rtcConfiguration = {
    iceServers: [
        {
            urls: ['stun:stun.miwifi.com:3478'],
        },
        {
            urls: ['turn:162.14.76.155:3478'],
            username: 'cd260930',
            credential: 'hsscd123adcx.',
        },
        {
            urls: ['turn:124.223.38.110:3478'],
            username: 'hsssh',
            credential: 'hssabc.x',
        },
    ],
    iceCandidatePoolSize: 10,
};

function startWebRtcPreview() {
    const video = document.getElementById('cam-preview');
    video.playbackRate = 1.0;
    // Always starts muted -- required for autoplay to not be blocked (confirmed on-device: even
    // unmuting already-playing media can still get the whole element paused in this environment,
    // so there's no reliable way to auto-restore a "the user wants sound" preference across a
    // reload without a real gesture). The user can unmute via the <video controls> element's own
    // native volume control, which is a real gesture and isn't subject to this.
    video.muted = true;

    const pc = new RTCPeerConnection(rtcConfiguration);
    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });
    pc.ontrack = (event) => {
        if (video.srcObject !== event.streams[0]) video.srcObject = event.streams[0];
    };

    const wsProtocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(wsProtocol + '//' + location.host + '/webrtc-ws');

    pc.onicecandidate = (event) => {
        if (!event.candidate) return;
        if (ws.readyState !== WebSocket.OPEN) return;
        ws.send(JSON.stringify({
            type: 'candidate',
            candidate: event.candidate.candidate,
            sdpMid: event.candidate.sdpMid,
            sdpMLineIndex: event.candidate.sdpMLineIndex,
        }));
    };

    ws.onopen = async () => {
        try {
            const offer = await pc.createOffer();
            await pc.setLocalDescription(offer);
            ws.send(JSON.stringify({ type: 'offer', sdp: offer.sdp }));
        } catch (e) {
            console.error('[webrtc] failed to create/send offer', e);
            ws.close();
        }
    };

    ws.onmessage = async (event) => {
        let msg;
        try {
            msg = JSON.parse(event.data);
        } catch (e) {
            return;
        }
        if (msg.type === 'answer') {
            await pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp });
        } else if (msg.type === 'candidate') {
            try {
                await pc.addIceCandidate({
                    candidate: msg.candidate,
                    sdpMid: msg.sdpMid,
                    sdpMLineIndex: msg.sdpMLineIndex,
                });
            } catch (e) {
                console.error('[webrtc] failed to add ICE candidate', e);
            }
        }
    };

    const reconnect = () => {
        pc.close();
        setTimeout(startWebRtcPreview, WEBRTC_PREVIEW.RECONNECT_DELAY_MS);
    };
    ws.onclose = reconnect;
    ws.onerror = () => ws.close();
}

if (typeof CCTV_PREVIEW_TYPE !== 'undefined' && CCTV_PREVIEW_TYPE === 'webrtc') {
    startWebRtcPreview();
}
