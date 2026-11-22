/* Minimal H.264 -> fragmented MP4 muxer for Media Source Extensions.
   Original code. ES5 only (old TV browsers). Works in browsers and node. */
(function (root) {
  'use strict';

  function u32(n) { return [(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255]; }
  function u16(n) { return [(n >>> 8) & 255, n & 255]; }
  function str(s) { var a = []; for (var i = 0; i < s.length; i++) a.push(s.charCodeAt(i)); return a; }
  function zeros(n) { var a = []; for (var i = 0; i < n; i++) a.push(0); return a; }

  // parts: arrays or Uint8Arrays
  function box(type, parts) {
    var size = 8, i;
    for (i = 0; i < parts.length; i++) size += parts[i].length;
    var out = new Uint8Array(size);
    out.set(u32(size), 0);
    out.set(str(type), 4);
    var off = 8;
    for (i = 0; i < parts.length; i++) { out.set(parts[i], off); off += parts[i].length; }
    return out;
  }
  function fullbox(type, version, flags, parts) {
    return box(type, [[version, (flags >>> 16) & 255, (flags >>> 8) & 255, flags & 255]].concat(parts));
  }

  // Split an Annex-B byte stream into NAL units (without start codes).
  function splitNals(buf) {
    var nals = [], n = buf.length, i = 0, start = -1;
    while (i + 2 < n) {
      if (buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 1) {
        if (start >= 0) {
          var end = i;
          while (end > start && buf[end - 1] === 0) end--; // trailing zero of a 4-byte start code
          nals.push(buf.subarray(start, end));
        }
        start = i + 3;
        i += 3;
      } else { i++; }
    }
    if (start >= 0 && start < n) nals.push(buf.subarray(start, n));
    return nals;
  }

  var MATRIX = [0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x40, 0, 0, 0];

  function Mp4Mux(width, height) {
    this.w = width; this.h = height;
    this.seq = 1;
    this.base = null;      // first pts (ms)
    this.pending = null;   // one-frame delay: a sample's duration is known when the next one arrives
    this.codec = null;
  }

  // sps, pps: Uint8Array NAL units without start codes. Returns init segment.
  Mp4Mux.prototype.init = function (sps, pps) {
    var hex = function (b) { return ('0' + b.toString(16)).slice(-2); };
    this.codec = 'avc1.' + hex(sps[1]) + hex(sps[2]) + hex(sps[3]);
    var ftyp = box('ftyp', [str('isom'), u32(512), str('isom'), str('iso6'), str('avc1'), str('mp41')]);
    var mvhd = fullbox('mvhd', 0, 0, [u32(0), u32(0), u32(1000), u32(0), u32(0x00010000), u16(0x0100),
      zeros(10), MATRIX, zeros(24), u32(2)]);
    var tkhd = fullbox('tkhd', 0, 7, [u32(0), u32(0), u32(1), u32(0), u32(0), zeros(8), u16(0), u16(0), u16(0),
      u16(0), MATRIX, u32(this.w * 65536), u32(this.h * 65536)]);
    var mdhd = fullbox('mdhd', 0, 0, [u32(0), u32(0), u32(1000), u32(0), u16(0x55c4), u16(0)]);
    var hdlr = fullbox('hdlr', 0, 0, [u32(0), str('vide'), zeros(12), [0x56, 0x69, 0x64, 0x65, 0x6f, 0]]);
    var vmhd = fullbox('vmhd', 0, 1, [u16(0), zeros(6)]);
    var dinf = box('dinf', [fullbox('dref', 0, 0, [u32(1), fullbox('url ', 0, 1, [])])]);
    var avcC = box('avcC', [[1, sps[1], sps[2], sps[3], 0xff, 0xe1], u16(sps.length), sps, [1], u16(pps.length), pps]);
    var avc1 = box('avc1', [zeros(6), u16(1), zeros(16), u16(this.w), u16(this.h), u32(0x00480000), u32(0x00480000),
      u32(0), u16(1), zeros(32), u16(0x18), [0xff, 0xff], avcC]);
    var stbl = box('stbl', [
      fullbox('stsd', 0, 0, [u32(1), avc1]),
      fullbox('stts', 0, 0, [u32(0)]),
      fullbox('stsc', 0, 0, [u32(0)]),
      fullbox('stsz', 0, 0, [u32(0), u32(0)]),
      fullbox('stco', 0, 0, [u32(0)])
    ]);
    var minf = box('minf', [vmhd, dinf, stbl]);
    var mdia = box('mdia', [mdhd, hdlr, minf]);
    var trak = box('trak', [tkhd, mdia]);
    var mvex = box('mvex', [fullbox('trex', 0, 0, [u32(1), u32(1), u32(0), u32(0), u32(0)])]);
    var moov = box('moov', [mvhd, trak, mvex]);
    var out = new Uint8Array(ftyp.length + moov.length);
    out.set(ftyp, 0); out.set(moov, ftyp.length);
    return out;
  };

  function toAvcc(annexb) {
    var nals = splitNals(annexb), keep = [], size = 0, i;
    for (i = 0; i < nals.length; i++) {
      var t = nals[i][0] & 31;
      if (t === 7 || t === 8 || t === 9) continue; // parameter sets live in avcC; drop AUD
      keep.push(nals[i]); size += 4 + nals[i].length;
    }
    var out = new Uint8Array(size), off = 0;
    for (i = 0; i < keep.length; i++) {
      out.set(u32(keep[i].length), off); off += 4;
      out.set(keep[i], off); off += keep[i].length;
    }
    return out;
  }

  Mp4Mux.prototype._fragment = function (s, duration) {
    var tfdtTime = s.pts - this.base;
    var build = function (dataOffset, seq) {
      var mfhd = fullbox('mfhd', 0, 0, [u32(seq)]);
      var tfhd = fullbox('tfhd', 0, 0x020000, [u32(1)]);
      var tfdt = fullbox('tfdt', 1, 0, [u32(0), u32(tfdtTime)]);
      var trun = fullbox('trun', 0, 0x000701, [u32(1), u32(dataOffset), u32(duration), u32(s.data.length),
        u32(s.key ? 0x02000000 : 0x01010000)]);
      return box('moof', [mfhd, box('traf', [tfhd, tfdt, trun])]);
    };
    var moof = build(0, this.seq);
    moof = build(moof.length + 8, this.seq);
    this.seq++;
    var mdat = box('mdat', [s.data]);
    var out = new Uint8Array(moof.length + mdat.length);
    out.set(moof, 0); out.set(mdat, moof.length);
    return out;
  };

  // annexb: one access unit. Returns an array with 0 or 1 media segments.
  Mp4Mux.prototype.push = function (annexb, ptsMs, isKey) {
    var cur = { data: toAvcc(annexb), pts: ptsMs, key: !!isKey };
    if (this.base === null) this.base = ptsMs;
    var out = [];
    if (this.pending) {
      var d = cur.pts - this.pending.pts;
      if (d < 1) d = 1;
      out.push(this._fragment(this.pending, d));
    }
    this.pending = cur;
    return out;
  };

  Mp4Mux.prototype.flush = function (duration) {
    if (!this.pending) return [];
    var seg = this._fragment(this.pending, duration || 33);
    this.pending = null;
    return [seg];
  };

  var api = { Mp4Mux: Mp4Mux, splitNals: splitNals };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.BridgeMux = api;
})(typeof window !== 'undefined' ? window : this);
