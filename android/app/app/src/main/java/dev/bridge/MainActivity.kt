package dev.bridge

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** Setup screen: Developer options -> Wireless debugging -> pair once -> connect -> choose an app. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var code: EditText
    private lateinit var hostPort: EditText

    private fun ui(f: () -> Unit) = runOnUiThread(f)
    private fun say(s: String) = ui { status.text = s }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, 24, 0, 8) }.also { col.addView(it) }
        fun button(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }.also { col.addView(it) }

        label("HIDDEN-APP MODE (main way): the app runs on a hidden display, your phone screen stays free.")
        label("1. Turn on Developer options, then Wireless debugging (phone must be on Wi-Fi). Pick \"Always allow on this network\" when asked.")
        button("Open Developer options") { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        label("2. Wireless debugging > Pair device with pairing code. Type the code here (the address fills in by itself).")
        code = EditText(this).apply { hint = "6-digit pairing code"; inputType = InputType.TYPE_CLASS_NUMBER }.also { col.addView(it) }
        hostPort = EditText(this).apply { hint = "pairing address, like 192.168.1.5:37123 (optional)" }.also { col.addView(it) }
        button("Pair (once)") { pair() }
        label("3. Connect")
        button("Connect") { connect() }
        label("4. Choose the app to run on the TV")
        button("Choose app") { startActivity(Intent(this, AppPickerActivity::class.java)) }
        label("FALLBACK, SCREEN-MIRROR MODE (use only if hidden-app mode fails on this phone or for one app): shows your phone's real screen on the TV. Turn on the remote-control service so the TV remote can tap.")
        button("Enable remote control (Accessibility)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("Start screen mirroring") {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), 42)
        }
        label("Both modes")
        button("Stop bridge") { stopService(Intent(this, BridgeService::class.java)); say("Stopped.") }
        status = TextView(this).apply { textSize = 15f; gravity = Gravity.START; setPadding(0, 24, 0, 0) }.also { col.addView(it) }
        setContentView(ScrollView(this).apply { addView(col) })
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        say(if (BridgeService.adbReady) "Connected to this phone's debug service." else "Not connected.")
    }

    private fun pair() {
        val c = code.text.toString().trim()
        if (c.length != 6) { say("Enter the 6-digit code from the pairing dialog."); return }
        say("Pairing...")
        val doPair = { host: String, port: Int ->
            thread {
                try {
                    val ok = AdbBridge.get(this).pair(host, port, c)
                    say(if (ok) "Paired. Now tap Connect." else "Pairing refused. Check the code, and keep the pairing dialog open.")
                } catch (e: Exception) { say("Pairing failed: ${e.message}") }
            }
        }
        val typed = hostPort.text.toString().trim()
        if (typed.contains(":")) doPair(typed.substringBefore(":"), typed.substringAfter(":").toInt())
        else Nsd.find(this, Nsd.PAIRING, { h, p -> doPair(h, p) }, { say("Pairing service not found. Keep the pairing dialog open or type the address shown in it.") })
    }

    private fun connect() {
        say("Connecting...")
        Nsd.find(this, Nsd.CONNECT, { h, p ->
            thread {
                try {
                    val ok = AdbBridge.get(this).connect(h, p)
                    BridgeService.adbReady = ok
                    say(if (ok) "Connected. Choose an app." else "Connection refused. Is Wireless debugging on, and is this phone paired?")
                } catch (e: Exception) { say("Connect failed: ${e.message}") }
            }
        }, { say("Wireless debugging not found. Is it on, and are you on Wi-Fi?") })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 42 && res == RESULT_OK && data != null) {
            androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java)
                .putExtra(BridgeService.EXTRA_MODE, BridgeService.MODE_MIRROR)
                .putExtra(BridgeService.EXTRA_CODE, res).putExtra(BridgeService.EXTRA_DATA, data))
            say("Mirroring. Open http://" + BridgeService.localIp() + ":" + BridgeService.PORT + " in the TV browser.")
        }
    }
}
