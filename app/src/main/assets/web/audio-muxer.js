/*
 * Hand-written audio-only fmp4 muxer for the dashboard's MSE preview -- there's no
 * "h264-converter.js"-equivalent library for AAC, so this is this project's own code, ported
 * line-for-line from its old (deleted) server-side Fmp4Fragmenter.kt's audio-track box builders
 * (git history commit 71667a3), just in JS. A single AAC-LC (`mp4a`) track, meant to be added as
 * a second SourceBuffer on h264-converter.js's own MediaSource (`converter.mediaSource`) rather
 * than a separate MediaSource of its own -- MSE keeps two SourceBuffers on the same MediaSource
 * in sync automatically, and each one gets its own independent ftyp+moov, no need to merge them
 * into one combined moov. See dashboard.html for usage
 * (`new AudioTrackAppender(mediaSource, sampleRate, channelCount, audioConfigBytes, framesPerFragment)`).
 */
(function (global) {
    "use strict";

    const AUDIO_TRACK_ID = 1;
    const MOVIE_TIMESCALE = 1000;
    const AAC_SAMPLES_PER_FRAME = 1024;

    function u8(v) { return new Uint8Array([v & 0xff]); }
    function u16(v) { return new Uint8Array([(v >>> 8) & 0xff, v & 0xff]); }
    function u24(v) { return new Uint8Array([(v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff]); }
    function u32(v) { return new Uint8Array([(v >>> 24) & 0xff, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff]); }
    // v fits well within Number.MAX_SAFE_INTEGER for any realistic session length.
    function u64(v) { return concatAll([u32(Math.floor(v / 0x100000000)), u32(v >>> 0)]); }
    function strBytes(s) {
        const out = new Uint8Array(s.length);
        for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
        return out;
    }
    function concatAll(parts) {
        let total = 0;
        for (const p of parts) total += p.length;
        const out = new Uint8Array(total);
        let offset = 0;
        for (const p of parts) { out.set(p, offset); offset += p.length; }
        return out;
    }
    function box(type, ...payload) {
        const size = 8 + payload.reduce((sum, p) => sum + p.length, 0);
        return concatAll([u32(size), strBytes(type), ...payload]);
    }
    // MPEG-4 descriptor "expandable" size: 7 bits/byte, continuation bit set except the last.
    function descriptor(tag, ...payload) {
        let length = payload.reduce((sum, p) => sum + p.length, 0);
        const sizeBytes = [];
        do {
            sizeBytes.unshift(length & 0x7f);
            length >>>= 7;
        } while (length > 0);
        for (let i = 0; i < sizeBytes.length - 1; i++) sizeBytes[i] |= 0x80;
        return concatAll([new Uint8Array([tag, ...sizeBytes]), ...payload]);
    }

    const UNITY_MATRIX = concatAll([
        u32(0x00010000), u32(0), u32(0),
        u32(0), u32(0x00010000), u32(0),
        u32(0), u32(0), u32(0x40000000),
    ]);

    function sampleEntryHeader() { return concatAll([new Uint8Array(6), u16(1)]); }

    function ftyp() {
        return box('ftyp',
            strBytes('isom'), u32(512),
            strBytes('isom'), strBytes('iso2'), strBytes('mp41'));
    }

    function mvhd(timescale) {
        return box('mvhd',
            concatAll([u8(0), u24(0)]), u32(0), u32(0), u32(timescale), u32(0),
            u32(0x00010000), concatAll([u16(0x0100), u16(0)]), concatAll([u32(0), u32(0)]), UNITY_MATRIX,
            new Uint8Array(24), u32(AUDIO_TRACK_ID + 1));
    }

    function tkhd(trackId, width, height, volume) {
        return box('tkhd',
            concatAll([u8(0), u24(0x000007)]), u32(0), u32(0), u32(trackId), u32(0), u32(0),
            concatAll([u32(0), u32(0)]), u16(0), u16(0), concatAll([u16(volume), u16(0)]), UNITY_MATRIX,
            u32(width << 16), u32(height << 16));
    }

    function mdhd(timescale) {
        return box('mdhd', concatAll([u8(0), u24(0)]), u32(0), u32(0), u32(timescale), u32(0), u16(0x55c4), u16(0));
    }

    function hdlr(handlerType, name) {
        return box('hdlr', concatAll([u8(0), u24(0)]), u32(0), strBytes(handlerType), new Uint8Array(12),
            concatAll([strBytes(name), new Uint8Array([0])]));
    }

    function smhd() { return box('smhd', concatAll([u8(0), u24(0)]), u16(0), u16(0)); }

    function dinf() {
        const url = box('url ', concatAll([u8(0), u24(1)]));
        return box('dinf', box('dref', concatAll([u8(0), u24(0)]), u32(1), url));
    }

    function stsd(sampleEntry) { return box('stsd', concatAll([u8(0), u24(0)]), u32(1), sampleEntry); }
    function emptyStts() { return box('stts', concatAll([u8(0), u24(0)]), u32(0)); }
    function emptyStsc() { return box('stsc', concatAll([u8(0), u24(0)]), u32(0)); }
    function emptyStsz() { return box('stsz', concatAll([u8(0), u24(0)]), u32(0), u32(0)); }
    function emptyStco() { return box('stco', concatAll([u8(0), u24(0)]), u32(0)); }
    function stbl(stsdBox) { return box('stbl', stsdBox, emptyStts(), emptyStsc(), emptyStsz(), emptyStco()); }

    // avgBitrate/maxBitrate here are advisory only -- browsers decode from the bitstream, not these.
    function esds(audioSpecificConfig, bitrateBps) {
        const decoderSpecificInfo = descriptor(0x05, audioSpecificConfig);
        const decoderConfig = descriptor(
            0x04, new Uint8Array([0x40]), new Uint8Array([0x15]), u24(0), u32(bitrateBps), u32(bitrateBps),
            decoderSpecificInfo);
        const slConfig = descriptor(0x06, new Uint8Array([0x02]));
        const esDescriptor = descriptor(0x03, u16(0), new Uint8Array([0x00]), decoderConfig, slConfig);
        return box('esds', concatAll([u8(0), u24(0)]), esDescriptor);
    }

    function mp4a(sampleRate, channelCount, esdsBox) {
        const body = concatAll([
            sampleEntryHeader(), u32(0), u32(0),
            u16(channelCount), u16(16), u16(0), u16(0),
            u32(sampleRate << 16),
        ]);
        return box('mp4a', body, esdsBox);
    }

    function mfhd(seq) { return box('mfhd', concatAll([u8(0), u24(0)]), u32(seq)); }
    // default-base-is-moof (0x020000) -- no other tfhd fields, trun carries everything per-sample.
    function tfhd(trackId) { return box('tfhd', concatAll([u8(0), u24(0x020000)]), u32(trackId)); }
    // Version 1 (64-bit) -- generous headroom for a 24/7 camera's accumulated decode time.
    function tfdt(baseMediaDecodeTime) { return box('tfdt', concatAll([u8(1), u24(0)]), u64(baseMediaDecodeTime)); }

    // One (duration, size) pair per sample -- no per-sample flags needed (audio has no
    // keyframe concept), so this is the minimal trun layout for N same-duration samples.
    function trun(durationUnits, sampleSizes, dataOffset) {
        const flags = 0x000001 | 0x000100 | 0x000200; // data-offset + duration + size present
        const parts = [concatAll([u8(0), u24(flags)]), u32(sampleSizes.length), u32(dataOffset)];
        for (const size of sampleSizes) { parts.push(u32(durationUnits)); parts.push(u32(size)); }
        return box('trun', ...parts);
    }

    function trex(trackId) {
        return box('trex', concatAll([u8(0), u24(0)]), u32(trackId), u32(1), u32(0), u32(0), u32(0));
    }

    // Builds moof+mdat for a batch of same-duration AAC frames, computing trun's
    // data_offset from the fixed-size boxes around it.
    function buildFragment(seq, baseMediaDecodeTime, frames) {
        const sizes = frames.map((f) => f.length);
        const mfhdBox = mfhd(seq);
        const tfhdBox = tfhd(AUDIO_TRACK_ID);
        const tfdtBox = tfdt(baseMediaDecodeTime);
        const trunPlaceholder = trun(AAC_SAMPLES_PER_FRAME, sizes, 0);
        const trafSize = 8 + tfhdBox.length + tfdtBox.length + trunPlaceholder.length;
        const moofSize = 8 + mfhdBox.length + trafSize;
        const trunBox = trun(AAC_SAMPLES_PER_FRAME, sizes, moofSize + 8);
        const trafBox = box('traf', tfhdBox, tfdtBox, trunBox);
        const moofBox = box('moof', mfhdBox, trafBox);
        return concatAll([moofBox, box('mdat', concatAll(frames))]);
    }

    function buildInitSegment(sampleRate, channelCount, audioConfig) {
        const mp4aBox = mp4a(sampleRate, channelCount, esds(audioConfig, 64 * 1024));
        const minf = box('minf', smhd(), dinf(), stbl(stsd(mp4aBox)));
        const mdia = box('mdia', mdhd(sampleRate), hdlr('soun', 'SoundHandler'), minf);
        const trak = box('trak', tkhd(AUDIO_TRACK_ID, 0, 0, 0x0100), mdia);
        const mvex = box('mvex', trex(AUDIO_TRACK_ID));
        const moov = box('moov', mvhd(MOVIE_TIMESCALE), trak, mvex);
        return concatAll([ftyp(), moov]);
    }

    function AudioTrackAppender(mediaSource, sampleRate, channelCount, audioConfigBytes, framesPerFragment) {
        this.sourceBuffer = null;
        this.sampleCount = 0;
        this.sequenceNumber = 1;
        this.framesPerFragment = framesPerFragment || 1;
        this.pendingFrames = [];
        this.queue = [this.buildInit(sampleRate, channelCount, audioConfigBytes)];
        const mimeType = 'audio/mp4; codecs="mp4a.40.2"';
        const attach = () => {
            this.sourceBuffer = mediaSource.addSourceBuffer(mimeType);
            this.sourceBuffer.addEventListener('updateend', () => {
                const next = this.queue.shift();
                if (next) this.doAppend(next);
            });
            this.sourceBuffer.addEventListener('error', () => console.error('[audio-muxer] SourceBuffer errored'));
            const first = this.queue.shift();
            if (first) this.doAppend(first);
        };
        if (mediaSource.readyState === 'open') attach();
        else mediaSource.addEventListener('sourceopen', attach, { once: true });
    }
    AudioTrackAppender.prototype.buildInit = function (sampleRate, channelCount, audioConfigBytes) {
        return buildInitSegment(sampleRate, channelCount, audioConfigBytes);
    };
    AudioTrackAppender.prototype.doAppend = function (data) {
        if (!this.sourceBuffer || this.sourceBuffer.updating) {
            this.queue.push(data);
            return;
        }
        try {
            this.sourceBuffer.appendBuffer(data);
        } catch (e) {
            console.error('[audio-muxer] appendBuffer failed', e);
        }
    };
    // Batches frames before appending, same reasoning as h264-converter.js's
    // framesPerFragment: h264-converter.js's own per-NAL appendBuffer() calls were
    // ~5/sec after batching video, and per-frame audio (~43/sec, since AAC frames are
    // much shorter than video frames) ended up the more frequent of the two -- more
    // total appendBuffer() calls/sec than video-only ever had, and made the on-device
    // decoder stall noticeably more frequent. Batching brings audio's append rate down
    // to roughly the same order of magnitude as video's.
    AudioTrackAppender.prototype.appendRawFrame = function (aacBytes) {
        this.pendingFrames.push(aacBytes);
        if (this.pendingFrames.length < this.framesPerFragment) return;
        const frames = this.pendingFrames;
        this.pendingFrames = [];
        const fragment = buildFragment(this.sequenceNumber++, this.sampleCount, frames);
        this.sampleCount += AAC_SAMPLES_PER_FRAME * frames.length;
        this.doAppend(fragment);
    };

    global.AudioTrackAppender = AudioTrackAppender;
})(window);
