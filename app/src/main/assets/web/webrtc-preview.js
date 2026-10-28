// --- WebRTC live preview (opt-in via the dashboard's preview-type chip, see dashboard.js) ---
// Signaling over /webrtc-ws (see WebRtcSignalingSocket.kt): this side always initiates with an
// SDP offer built from a recvonly video transceiver, the phone answers with its shared camera
// track, and ICE candidates are relayed both ways as small JSON messages.
//
// Video-only: audio is a separate, shared preview independent of which video transport is active
// -- see audio-preview.js and AudioStreamBridge.kt's kdoc for why.
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

    const pc = new RTCPeerConnection(rtcConfiguration);
    pc.addTransceiver('video', { direction: 'recvonly' });
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
