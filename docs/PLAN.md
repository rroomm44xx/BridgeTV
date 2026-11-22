# Bridge: plan (v4, Android 11+ hidden display, screen-mirror fallback)

Goal: pick an app on the phone, have it run on a hidden display so the phone screen stays free, and see and control it on the TV. Original code. Targets: LG 49UM7340PVA (webOS) and Samsung UA50U8000FUXSA (Tizen), one TV web app for both. Phone: Android 11 or newer (the user's devices are all Android 11+). Primary mode: hidden display. Fallback: screen mirror.

This version follows how the user described the original Morph Bridge: developer options and Wireless debugging trust, an app picker, apps running without the phone itself showing them. The original's own code is not here, so the design below is my reconstruction of the standard technique (the same one scrcpy's virtual display uses), not a copy.

## Android versions: Android 11+ only, two modes, same TV web app
The user confirmed every device is Android 11 or newer, so minSdk is 30 (Wireless debugging pairing starts at API 30). Older phones are not supported; the code for them was removed.

- Primary: hidden-app mode (virtual display + wireless debugging). Phone screen stays free.
- Fallback, costs almost nothing extra because the encoder, server and TV page are shared: screen-mirror mode (MediaProjection) with TV input through an Accessibility service. Use it if hidden-app mode fails on one phone (a maker blocks it) or for one app that will not run on the hidden display. The phone is in use while it runs.

| Android | Audio route that exists |
| --- | --- |
| 11 (API 30) | REMOTE_SUBMIX needs workarounds (scrcpy notes the screen must be unlocked at start). AudioPlaybackCapture API exists (since API 29) but needs mirror-mode consent and apps can opt out |
| 12 and newer (API 31+) | REMOTE_SUBMIX works out of the box per scrcpy; AudioPlaybackCapture-style capture with duplicate playback on 13+ |

Version gates: wireless-debugging pairing API 30+; AudioPlaybackCapture API 29+; foreground service type special use needs API 34+ (plain foreground service below that); media projection foreground type for the fallback; Android 14+ needs the projection callback registered before the display is created and asks for screen-share consent each time. The TV web app is identical in both modes.

## What runs where
Phone app (Kotlin), hidden-app mode:
1. Self-pairing: the app pairs once with the phone's own Wireless debugging (6-digit code), then connects to its own adbd over Wi-Fi. It gets "shell" powers without root. It uses a third-party ADB client library (libadb-android, Apache-2.0 option) for the pairing and TLS handshake, and BouncyCastle to make its key and certificate.
2. Hidden display: the app creates a VirtualDisplay (1280x720, landscape, 240 dpi) whose surface feeds an H.264 encoder. No screen-capture consent, the real screen is untouched.
3. App picker: lists launchable apps. The chosen one is started on the hidden display with `am start --display ID -n pkg/activity`, run through the app's own ADB connection. `force_resizable_activities` is switched on for the session so more apps accept a secondary display, and restored on stop.
4. Input: TV remote or Magic Remote events come over the WebSocket and become `input -d ID tap|swipe|keyevent` shell commands aimed at the hidden display.
5. One server on port 8080: serves the TV web app (HTTP) and carries stream + input (WebSocket /ws).

TV (web app, ES5): unchanged from v1. Opens ws://phone:8080/ws, wraps H.264 into fragmented MP4 (mp4mux.js), plays with Media Source Extensions at the live edge, cursor + OK/Back/Home from the remote, Magic Remote pointer as touch. First run in the TV's built-in browser, packaging as IPK/WGT later.

## Setup flow the user sees
Enable Developer options -> Wireless debugging (phone on Wi-Fi, "Always allow on this network") -> "Pair device with pairing code" -> type the code in Bridge -> Connect -> Choose app -> open http://PHONE_IP:8080 on the TV.

## Milestones
1. Build and run (Android Studio). Fix first-compile issues, especially libadb-android API names, then pair, connect, see one app on the TV browser.
2. Reliability: reconnect when Wireless debugging port changes, handle Wi-Fi changes, clear errors in the app.
3. Faster input: each `input` command starts a process (about 100 to 300 ms). Replace with a small helper process started through ADB (`app_process`, runs as shell) that injects events directly over a local socket. Same helper is needed for audio (milestone 4).
4. Audio (below).
5. Pairing/PIN so other devices on the Wi-Fi cannot open the stream. Currently open on the LAN.
6. Package the TV app as webOS IPK and Tizen WGT, add discovery instead of typing the IP.
7. Optional: app picker on the TV screen itself.

## Audio: options and honest assessment
Problem: apps on the hidden display play through the phone's speaker. Nothing sends that sound to the TV yet, which matches the original's known problem.

Option A, REMOTE_SUBMIX capture (Android 11+). Records the whole device audio output and, as scrcpy documents, "disables playback on the device". That is the behaviour wanted: sound leaves the phone and goes to the TV. Source: https://raw.githubusercontent.com/Genymobile/scrcpy/master/doc/audio.md . It needs shell-level privilege, so it must run in a helper process started through our ADB connection (milestone 3 helper), and on Android 11 scrcpy notes extra workarounds (screen unlocked at start); Android 12+ works out of the box there. Catches: it captures all phone audio, not only the chosen app (notifications and calls too, since playback on the phone is off); the helper uses hidden Android APIs, so it can break on a new Android release or an OEM build; I have not tested it on the user's phone.
Option B, AudioPlaybackCapture (Android 10+ API; scrcpy's "playback" source needs Android 13+). Captures playback from chosen apps, and can keep the phone playing too (duplicate), which is the opposite of what is wanted unless the phone is muted. Apps can opt out of capture, so some apps give silence. Source: same scrcpy doc. Needs the screen-capture consent or shell identity. Whether muting phone volume also silences the capture is not documented in what I read; must be tested on the device.
Option C, workaround: user plugs in or pairs headphones or a Bluetooth speaker on the phone. Not a fix, only a fallback.

Recommendation: build A in milestone 4 on Android 12+, fall back to B where A fails, show "no audio" honestly when neither works.

Transport and sync: encode captured PCM to AAC-LC with MediaCodec on the phone, stamp it with the same clock as video frames, and add an audio track to the same fragmented MP4 so the TV's player keeps video and audio together. Opus in MSE is not safe on these TVs, AAC is. Expect roughly 150 to 300 ms end-to-end audio delay, so add a small manual lip-sync offset setting. Achievable: audio working on Android 12+ phones in most cases. Not guaranteed: DRM apps, apps that opt out under option B, OEM builds that restrict the hidden APIs.

## USB
Unchanged from v1: USB cannot carry this on these TVs. Stock webOS and Tizen TV USB ports are for storage, keyboards and mice; LG describes phone-over-USB as file access only (https://www.lg.com/us/support/help-library/lg-tv-how-to-connect-a-mobile-phone-to-your-lg-tv-via-an-usb-cable--20153110643228); USB networking on webOS needs a rooted TV (https://ohmybahgosh.dev/lg-usb-2-eth0/). Wired options that keep the app unchanged: USB-C Ethernet adapter into the router the TV is on, or the TV joined to the phone's hotspot. Note Wireless debugging needs Wi-Fi to be on, so a hotspot setup must be tested.

## Risks to know up front
- Some apps will not run on a secondary display (they close or show black). `force_resizable_activities` helps many, not all.
- Apps that block capture (DRM, FLAG_SECURE: banking, Netflix) show black.
- Wireless debugging turns itself off when Wi-Fi changes; the port changes each time it is switched on.
- Some phone makers add a separate switch for injecting input over debugging (for example "USB debugging (security settings)" on some Xiaomi/MIUI phones). If taps do nothing, check Developer options for it.
- Whether PUBLIC virtual displays can host other apps' activities without extra flags varies by Android version and maker. If milestone 1 fails here, the fallback is to create the display inside the shell helper (scrcpy does this), which is milestone 3 work.
- The libadb-android library carries its own security warning: it has never been audited. Wireless debugging is also powerful. The app only talks to its own phone, but treat the pairing as giving the app shell access to the phone.

## What is tested
- mp4mux.js: verified earlier with ffmpeg and ffprobe (90 frames in, 90 out, decodes cleanly).
- TV JS: syntax checked only.
- Android code (both modes): written, not compiled or run (no Android SDK here). libadb-android calls (pair, connect, openStream) are from its README and memory of its API: expect to adjust names. Nothing has run on a real phone or TV.
