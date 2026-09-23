// --- Zoom slider ---
const zoomSlider = document.getElementById('zoomSlider');
const zoomValueText = document.getElementById('zoomValueText');
let zoomDragging = false;
let zoomDebounceTimer = null;
zoomSlider.addEventListener('pointerdown', () => { zoomDragging = true; });
zoomSlider.addEventListener('pointerup', () => { setTimeout(() => { zoomDragging = false; }, 400); });

function onZoomInput(v) {
    zoomValueText.textContent = parseFloat(v).toFixed(1) + 'x';
    clearTimeout(zoomDebounceTimer);
    zoomDebounceTimer = setTimeout(() => pushZoom(v), 120);
}
function onZoomChange(v) {
    clearTimeout(zoomDebounceTimer);
    pushZoom(v);
    showToast('Zoom: ' + parseFloat(v).toFixed(1) + 'x');
}
function pushZoom(v) {
    fetch('/action/set-setting?key=zoom_level&value=' + encodeURIComponent(parseFloat(v).toFixed(2)), POST);
}

function changeBitrate(v) {
    fetch('/action/set-setting?key=bitrate_kbps&value=' + encodeURIComponent(v), POST)
        .then(() => { showToast('Bitrate: ' + v + ' kbps'); fetchStatus(); });
}

function changeFps(v) {
    fetch('/action/set-setting?key=video_fps&value=' + encodeURIComponent(v), POST)
        .then(() => { showToast('Frame rate: ' + v + ' fps'); fetchStatus(); });
}

// --- Resolution options ---
// Populated once from the camera's real supported sizes (falls back to a
// small static list if the device somehow reports none), then left alone --
// fetchStatus() polls every 3s and rebuilding the <option> list every time
// would fight an open dropdown.
function populateResolutionOptions(options) {
    const select = document.getElementById('resSelect');
    if (select.dataset.populated) return;
    const sizes = (options && options.length > 0) ? options : ['640x480', '1280x720', '1920x1080'];
    sizes.forEach(function(res) {
        const opt = document.createElement('option');
        opt.value = res;
        opt.textContent = res;
        select.appendChild(opt);
    });
    select.dataset.populated = '1';
}

// --- Battery icon ---
// Same battery outline either way; the charging variant adds a bolt cut out of
// the solid fill (fill-rule="evenodd" on the <path>, set once in the markup,
// renders any inner subpath as a hole).
const BATTERY_PATH_PLAIN = 'M15.67 4H14V2h-4v2H8.33C7.6 4 7 4.6 7 5.33v15.33C7 21.4 7.6 22 8.33 22h7.33c.74 0 1.34-.6 1.34-1.33V5.33C17 4.6 16.4 4 15.67 4z';
const BATTERY_PATH_CHARGING = BATTERY_PATH_PLAIN + 'M13 6L10 13L12 13L11 20L15 11L13 11Z';

// --- Uptime formatting ---
// Days shown only once a full day has elapsed; hours shown only once a full
// hour has elapsed (so a fresh boot reads "3m", not "0d 0h 3m").
function formatUptime(ms) {
    let totalMinutes = Math.floor(ms / 60000);
    const days = Math.floor(totalMinutes / 1440);
    totalMinutes %= 1440;
    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    const parts = [];
    if (days > 0) parts.push(days + 'd');
    if (days > 0 || hours > 0) parts.push(hours + 'h');
    parts.push(minutes + 'm');
    return parts.join(' ');
}

// --- Status polling ---
let initialLoad = true;
function fetchStatus() {
    fetch('/status')
        .then(r => r.json())
        .then(data => {
            const badge = document.getElementById('statusBadge');
            const statusText = document.getElementById('statusText');
            const btnStream = document.getElementById('btnStream');
            const btnStreamText = document.getElementById('btnStreamText');
            const chipCodec = document.getElementById('chipCodec');
            const chipRes = document.getElementById('chipRes');
            const batteryBadge = document.getElementById('batteryBadge');
            const batteryText = document.getElementById('batteryText');
            const batteryIconPath = document.getElementById('batteryIconPath');
            const wifiText = document.getElementById('wifiText');
            const cpuTempText = document.getElementById('cpuTempText');
            const uptimeText = document.getElementById('uptimeText');

            if (data.batteryLevel >= 0) batteryText.textContent = data.batteryLevel + '%';
            batteryIconPath.setAttribute('d', data.isCharging === true ? BATTERY_PATH_CHARGING : BATTERY_PATH_PLAIN);
            batteryBadge.title = data.isCharging === true ? 'Battery (Charging)' : 'Battery';
            if (data.wifiStrength >= 0) wifiText.textContent = data.wifiStrength + '%';
            if (data.cpuTempCelsius !== null && data.cpuTempCelsius !== undefined) {
                cpuTempText.textContent = data.cpuTempCelsius.toFixed(1) + '°C';
            }
            if (typeof data.uptimeMillis === 'number') {
                uptimeText.textContent = formatUptime(data.uptimeMillis);
            }

            if (data.streaming) {
                badge.className = 'status-badge live';
                badge.title = 'LIVE';
                statusText.textContent = 'LIVE';
                btnStream.className = 'btn danger';
                btnStreamText.textContent = 'Stop';
            } else {
                badge.className = 'status-badge offline';
                badge.title = 'OFFLINE';
                statusText.textContent = 'OFFLINE';
                btnStream.className = 'btn primary';
                btnStreamText.textContent = 'Start';
            }

            chipCodec.textContent = data.codec;
            chipRes.textContent = data.resolution;

            // Sync dropdowns
            document.getElementById('codecSelect').value = data.codec;
            populateResolutionOptions(data.resolutionOptions);
            document.getElementById('resSelect').value = data.resolution;

            // Sync toggles
            document.getElementById('toggleTimestamp').checked = data.showTimestamp;
            document.getElementById('posSelect').value = data.timestampPosition;
            document.getElementById('sizeSelect').value = data.timestampSize;
            document.getElementById('toggleFlashlight').checked = data.flashlightEnabled;
            document.getElementById('toggleAutoFocus').checked = data.autoFocusEnabled;
            document.getElementById('toggleNightMode').checked = data.nightModeEnabled;
            document.getElementById('toggleVerticalFlip').checked = data.verticalFlipEnabled;
            if (!zoomDragging) {
                zoomSlider.min = data.zoomMin;
                zoomSlider.max = data.zoomMax;
                zoomSlider.value = data.zoomLevel;
                zoomValueText.textContent = Number(data.zoomLevel).toFixed(1) + 'x';
            }
            document.getElementById('bitrateSelect').value = data.bitrateKbps;
            document.getElementById('fpsSelect').value = data.videoFps;

            // Sync recording
            document.getElementById('toggleRecordToGallery').checked = data.recordToGalleryEnabled;
            if (document.activeElement.id !== 'recordSegmentMinutes') {
                document.getElementById('recordSegmentMinutes').value = data.recordSegmentMinutes;
            }
            document.getElementById('chipRec').style.display = data.isRecordingToGallery ? 'inline-block' : 'none';

            // Sync auth
            document.getElementById('toggleAuth').checked = data.authEnabled;
            document.getElementById('toggleWebAuth').checked = data.webAuthEnabled;
            if (initialLoad) {
                document.getElementById('authUsername').value = data.username || '';
                initialLoad = false;
            }

            // Update RTSP URL dynamically
            if (data.rtspUrl) {
                document.getElementById('rtspUrl').textContent = data.rtspUrl;
            }
            var authBadge = document.getElementById('authBadge');
            if (authBadge) {
                authBadge.style.display = data.authEnabled ? 'inline' : 'none';
            }
        })
        .catch(function() {});
}
// --- Preview transport toggle ---
// The choice is stuck in localStorage (per browser, not per device -- see dashboard.html's
// CCTV_PREVIEW_TYPE bootstrap) and applied by reloading, rather than tearing down/handing off
// in-place -- mse-preview.js/webrtc-preview.js each own their own connect/reconnect lifecycle
// and neither exposes a teardown hook the other could call.
function togglePreviewType() {
    const isWebRtc = typeof CCTV_PREVIEW_TYPE !== 'undefined' && CCTV_PREVIEW_TYPE === 'webrtc';
    localStorage.setItem('cctv-preview-type', isWebRtc ? 'mse' : 'webrtc');
    location.reload();
}
(function initPreviewTypeChip() {
    const chip = document.getElementById('chipPreviewType');
    if (!chip) return;
    const isWebRtc = typeof CCTV_PREVIEW_TYPE !== 'undefined' && CCTV_PREVIEW_TYPE === 'webrtc';
    // Shows what tapping it switches TO, not the current mode -- a call-to-action label,
    // not a status readout (chipCodec/chipRes already cover "what's active right now").
    const target = isWebRtc ? 'MSE' : 'RTC';
    chip.textContent = target;
    chip.title = 'Tap to switch to ' + target;
    chip.onclick = togglePreviewType;
})();

fetchStatus();
setInterval(fetchStatus, 3000);

// --- Actions ---
// State changes go out as POST; the server also accepts GET for
// backwards compatibility with existing scripts and NVR integrations.
const POST = { method: 'POST' };
function toggleStream() {
    fetch('/action/toggle-stream', POST)
        .then(r => r.text())
        .then(t => { showToast(t === 'Started' ? 'Stream started' : 'Stream stopped'); fetchStatus(); });
}

function switchCamera() {
    fetch('/action/switch-camera', POST)
        .then(() => showToast('Camera switched'));
}

function changeCodec(v) {
    fetch('/action/set-codec?codec=' + encodeURIComponent(v), POST)
        .then(() => { showToast('Codec: ' + v); fetchStatus(); });
}

function changeResolution(v) {
    const [w, h] = v.split('x');
    fetch('/action/set-resolution?w=' + w + '&h=' + h, POST)
        .then(() => { showToast('Resolution: ' + v); fetchStatus(); });
}

function setSetting(key, value) {
    fetch('/action/set-setting?key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(value), POST)
        .then(() => { showToast(key.replace(/_/g, ' ') + ': ' + value); fetchStatus(); });
}

function updateAuth() {
    const enabled = document.getElementById('toggleAuth').checked;
    const username = document.getElementById('authUsername').value;
    const password = document.getElementById('authPassword').value;
    fetch('/action/set-auth?enabled=' + enabled + '&username=' + encodeURIComponent(username) + '&password=' + encodeURIComponent(password), POST)
        .then(() => { showToast('Auth ' + (enabled ? 'enabled' : 'disabled')); fetchStatus(); });
}

function copyUrl(id) {
    const text = document.getElementById(id).textContent;
    navigator.clipboard.writeText(text).then(() => showToast('Copied to clipboard'));
}

// --- Toast ---
let toastTimer;
function showToast(msg) {
    const toast = document.getElementById('toast');
    toast.textContent = msg;
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('show'), 2500);
}
