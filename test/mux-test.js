// node mux-test.js in.h264 out.mp4   (in.h264 = Annex-B, single slice per frame)
var fs = require('fs');
var M = require('../android/app/src/main/assets/tv/mp4mux.js');
var buf = new Uint8Array(fs.readFileSync(process.argv[2]));
var w = +process.argv[4] || 640, h = +process.argv[5] || 360;
var nals = M.splitNals(buf), sps, pps, acc = [], frames = [];
nals.forEach(function (n) {
  var t = n[0] & 31;
  if (t === 7) sps = n; else if (t === 8) pps = n;
  if (t === 1 || t === 5) { // VCL: one frame
    var parts = acc.concat([n]), len = 0; parts.forEach(function (p) { len += 4 + p.length; });
    var f = new Uint8Array(len), o = 0;
    parts.forEach(function (p) { f.set([0, 0, 0, 1], o); f.set(p, o + 4); o += 4 + p.length; });
    frames.push({ d: f, key: t === 5 }); acc = [];
  } else if (t !== 7 && t !== 8) { /* ignore SEI etc */ } 
});
var mux = new M.Mp4Mux(w, h), out = [Buffer.from(mux.init(sps, pps))];
frames.forEach(function (f, i) { mux.push(f.d, Math.round(i * 1000 / 30), f.key).forEach(function (s) { out.push(Buffer.from(s)); }); });
mux.flush(33).forEach(function (s) { out.push(Buffer.from(s)); });
fs.writeFileSync(process.argv[3], Buffer.concat(out));
console.log('frames', frames.length, 'codec', mux.codec);
