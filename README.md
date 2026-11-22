# Bridge (MVP scaffold v4)
Android 11+ phones. Main way: hidden-app mode (pair with Wireless debugging, pick an app, it runs on a hidden display).
Fallback: screen-mirror mode (copies the phone screen, TV remote taps through an Accessibility service). Same TV web app for both.

1. Open `android/` in Android Studio (Hedgehog or newer, JDK 17). Sync Gradle (needs internet). Run on a phone.
2. Follow the steps in the app.
3. On the TV open the address shown (http://PHONE_IP:8080) in the web browser.
The TV app lives in `android/app/src/main/assets/tv/`. `test/mux-test.js` checks the muxer with ffmpeg. See docs/PLAN.md.
