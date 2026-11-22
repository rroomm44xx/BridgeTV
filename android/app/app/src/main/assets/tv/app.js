/* Bridge TV client. ES5 only: must parse on LG webOS 4 (Chromium 38) and Samsung Tizen 5.5 (Chromium 69).
   Protocol (phone -> TV): text JSON {"t":"hello","w":..,"h":..}; binary [type u8][pts u32 BE ms][payload]
   type 1 = codec config (Annex-B SPS+PPS), 2 = key frame, 3 = delta frame (Annex-B access units).
   Protocol (TV -> phone): JSON {"t":"tap","x":0..1,"y":0..1}, {"t":"swipe",...}, {"t":"key","k":"back|home|recents"}, {"t":"sync"} */
(function () {
  'use strict';
  var video = document.getElementById('v');
  var cursorEl = document.getElementById('cursor');
  var statusEl = document.getElementById('status');
  var ws, mux, ms, sb, queue, vw, vh, haveConfig, waitKey, objUrl, cx = 0.5, cy = 0.5, hideTimer;

  function say(s) { statusEl.textContent = s; statusEl.style.display = s ? 'block' : 'none'; }
  function send(o) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); }

  function resetPlayer() {
    queue = []; sb = null; mux = null; haveConfig = false; waitKey = true;
    try { if (ms && ms.readyState === 'open') ms.endOfStream(); } catch (e) {}
    ms = null;
    if (objUrl) { try { URL.revokeObjectURL(objUrl); } catch (e) {} objUrl = null; }
    video.removeAttribute('src');
  }

  function pump() {
    if (!sb || sb.updating || !queue.length) return;
    try { sb.appendBuffer(queue.shift()); } catch (e) { say('Buffer error, resyncing'); send({ t: 'sync' }); resetPlayer(); }
  }

  function startMse(initSeg, codec) {
    var type = 'video/mp4; codecs="' + codec + '"';
    if (!window.MediaSource || !MediaSource.isTypeSupported(type)) { say('This TV cannot play ' + type); return; }
    ms = new MediaSource();
    objUrl = URL.createObjectURL(ms);
    video.src = objUrl;
    ms.addEventListener('sourceopen', function () {
      if (sb) return;
      sb = ms.addSourceBuffer(type);
      sb.addEventListener('updateend', pump);
      queue.unshift(initSeg);
      pump();
    });
    var p = video.play(); if (p && p.catch) p.catch(function () {});
  }

  function onConfig(payload) {
    var nals = BridgeMux.splitNals(payload), sps, pps, i;
    for (i = 0; i < nals.length; i++) {
      var t = nals[i][0] & 31;
      if (t === 7) sps = nals[i]; else if (t === 8) pps = nals[i];
    }
    if (!sps || !pps || !vw) return;
    resetPlayer();
    mux = new BridgeMux.Mp4Mux(vw, vh);
    var init = mux.init(sps, pps);
    haveConfig = true; waitKey = true;
    startMse(init, mux.codec);
    send({ t: 'sync' });
  }

  function onFrame(isKey, pts, payload) {
    if (!mux || !haveConfig) return;
    if (waitKey) { if (!isKey) return; waitKey = false; }
    var segs = mux.push(payload, pts, isKey);
    for (var i = 0; i < segs.length; i++) queue.push(segs[i]);
    if (queue.length > 90) { queue = []; waitKey = true; send({ t: 'sync' }); } // TV can't keep up: drop and wait for a key frame
    pump();
  }

  // Keep playback at the live edge and trim old data.
  setInterval(function () {
    if (!sb || !video.buffered || !video.buffered.length) return;
    var end = video.buffered.end(video.buffered.length - 1);
    if (end - video.currentTime > 0.5) video.currentTime = end - 0.1;
    if (video.paused) { var p = video.play(); if (p && p.catch) p.catch(function () {}); }
    if (!sb.updating && video.currentTime - video.buffered.start(0) > 8) {
      try { sb.remove(video.buffered.start(0), video.currentTime - 3); } catch (e) {}
    }
  }, 400);

  function connect() {
    say('Connecting...');
    resetPlayer();
    ws = new WebSocket('ws://' + location.host + '/ws');
    ws.binaryType = 'arraybuffer';
    ws.onopen = function () { say('Waiting for phone screen...'); };
    ws.onmessage = function (ev) {
      if (typeof ev.data === 'string') {
        var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
        if (m.t === 'hello') { vw = m.w; vh = m.h; say(''); }
        return;
      }
      var a = new Uint8Array(ev.data);
      if (a.length < 6) return;
      var type = a[0], pts = ((a[1] << 24) | (a[2] << 16) | (a[3] << 8) | a[4]) >>> 0;
      var payload = a.subarray(5);
      if (type === 1) onConfig(payload); else if (type === 2) onFrame(true, pts, payload); else if (type === 3) onFrame(false, pts, payload);
    };
    ws.onclose = function () { say('Disconnected. Retrying...'); setTimeout(connect, 1500); };
    ws.onerror = function () { try { ws.close(); } catch (e) {} };
  }

  // ---- input: remote control + Magic Remote pointer ----
  function placeCursor() {
    cursorEl.style.left = (cx * 100) + '%'; cursorEl.style.top = (cy * 100) + '%';
    cursorEl.style.display = 'block';
    clearTimeout(hideTimer); hideTimer = setTimeout(function () { cursorEl.style.display = 'none'; }, 3000);
  }
  // Map a point in the TV window to 0..1 inside the letterboxed video.
  function videoRect() {
    var W = window.innerWidth, H = window.innerHeight;
    if (!vw) return { x: 0, y: 0, w: W, h: H };
    var s = Math.min(W / vw, H / vh), w = vw * s, h = vh * s;
    return { x: (W - w) / 2, y: (H - h) / 2, w: w, h: h };
  }
  function norm(px, py) {
    var r = videoRect();
    return { x: Math.max(0, Math.min(1, (px - r.x) / r.w)), y: Math.max(0, Math.min(1, (py - r.y) / r.h)) };
  }
  function tapAtCursor() { send({ t: 'tap', x: cx, y: cy }); }
  var step = 0.04;
  document.addEventListener('keydown', function (e) {
    var k = e.keyCode, handled = true;
    if (k === 37) cx = Math.max(0, cx - step);
    else if (k === 39) cx = Math.min(1, cx + step);
    else if (k === 38) cy = Math.max(0, cy - step);
    else if (k === 40) cy = Math.min(1, cy + step);
    else if (k === 13) tapAtCursor();
    else if (k === 461 || k === 10009 || k === 8) send({ t: 'key', k: 'back' });   // webOS back, Tizen back, backspace
    else if (k === 403 || k === 82) send({ t: 'key', k: 'home' });                  // red key / R
    else if (k === 404 || k === 71) send({ t: 'key', k: 'recents' });               // green key / G
    else if (k === 33 || k === 427) send({ t: 'swipe', x1: 0.5, y1: 0.3, x2: 0.5, y2: 0.7, ms: 250 }); // channel up: scroll up
    else if (k === 34 || k === 428) send({ t: 'swipe', x1: 0.5, y1: 0.7, x2: 0.5, y2: 0.3, ms: 250 }); // channel down: scroll down
    else handled = false;
    if (handled) { e.preventDefault(); placeCursor(); }
  });
  document.addEventListener('mousemove', function (e) { var n = norm(e.clientX, e.clientY); cx = n.x; cy = n.y; placeCursor(); });
  document.addEventListener('click', function (e) { var n = norm(e.clientX, e.clientY); cx = n.x; cy = n.y; placeCursor(); tapAtCursor(); });

  connect();
})();
