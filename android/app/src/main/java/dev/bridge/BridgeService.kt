package dev.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two modes, same stream and same TV web app:
 *  VIRTUAL (primary, Android 11+ which is the app's minimum): a hidden display; the chosen app is started on it over the app's own ADB
 *    connection, and TV input returns as `input -d ID ...` shell commands. The phone screen stays free.
 *  MIRROR (fallback if hidden mode fails on a phone or app): MediaProjection copies the real screen; TV input is injected by
 *    the accessibility service. The phone screen is in use while it runs.
 */
class BridgeService : Service() {
    companion object {
        const val PORT = 8080
        const val EXTRA_MODE = "mode"           // "virtual" | "mirror"
        const val EXTRA_COMPONENT = "component" // virtual mode: pkg/activity to launch
        const val EXTRA_CODE = "code"           // mirror mode: MediaProjection result
        const val EXTRA_DATA = "data"
        const val MODE_VIRTUAL = "virtual"
        const val MODE_MIRROR = "mirror"
        @Volatile var adbReady = false
        @Volatile var running = false

        fun localIp(): String = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { n -> n.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { it.hostAddress ?: "" } }
            .firstOrNull() ?: "PHONE_IP"
    }

    private var mode = MODE_VIRTUAL
    private var streamW = 1280
    private var streamH = 720
    private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var server: BridgeServer? = null
    private var drain: Thread? = null
    @Volatile private var stop = false
    private var displayId = -1
    private var oldResizable = "0"
    private val shellQ = Executors.newSingleThreadExecutor()
    private val queued = AtomicInteger(0)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val newMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VIRTUAL
        startNotice(newMode)
        if (running && newMode != mode) teardown()
        mode = newMode
        try {
            if (!running) { if (mode == MODE_MIRROR) startMirror(intent) else startVirtual(); running = true }
            if (mode == MODE_VIRTUAL) intent.getStringExtra(EXTRA_COMPONENT)?.let { launchApp(it) }
        } catch (e: Exception) { teardown(); stopSelf() }
        return START_NOT_STICKY
    }

    private fun startNotice(m: String) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("bridge", "Bridge", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bridge").setContentTitle("Bridge is streaming to your TV")
            .setSmallIcon(android.R.drawable.ic_menu_share).setOngoing(true).build()
        when {
            m == MODE_MIRROR -> startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            m == MODE_VIRTUAL && Build.VERSION.SDK_INT >= 34 -> startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else -> startForeground(1, n)
        }
    }

    /** Shared by both modes: server + H.264 encoder. Returns the encoder's input surface. */
    private fun startEncoderAndServer(): android.view.Surface {
        val srv = BridgeServer(this, PORT, onInput = { input(it) }, onClientNeedsKey = { requestKey() }, onClientConnected = { requestKey() })
        srv.hello = JSONObject().put("t", "hello").put("w", streamW).put("h", streamH).toString()
        srv.start(0); server = srv

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, streamW, streamH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            setInteger(MediaFormat.KEY_LATENCY, 0)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface(); enc.start(); codec = enc
        stop = false
        drain = Thread {
            val info = MediaCodec.BufferInfo()
            while (!stop) {
                val i = try { enc.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
                if (i < 0) continue
                val buf = enc.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size); buf.get(bytes)
                    val type = when {
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> 1
                        info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> 2
                        else -> 3
                    }
                    srv.broadcast(type, info.presentationTimeUs / 1000, bytes)
                }
                try { enc.releaseOutputBuffer(i, false) } catch (e: Exception) { break }
            }
        }.also { it.name = "bridge-drain"; it.start() }
        return surface
    }

    private fun startVirtual() {
        streamW = 1280; streamH = 720   // landscape for the TV, whatever the phone does
        val surface = startEncoderAndServer()
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        // PUBLIC lets another process (the shell) start activities on it; OWN_CONTENT_ONLY keeps the phone screen out of it.
        display = dm.createVirtualDisplay("bridge", streamW, streamH, 240, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        displayId = display!!.display.displayId
    }

    private fun startMirror(intent: Intent) {
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        requireNotNull(data)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val scale = 1280f / maxOf(dm.widthPixels, dm.heightPixels)
        streamW = ((dm.widthPixels * scale).toInt() / 16) * 16
        streamH = ((dm.heightPixels * scale).toInt() / 16) * 16
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mgr.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, null) // required on Android 14+
        projection = p
        val surface = startEncoderAndServer()
        display = p.createVirtualDisplay("bridge", streamW, streamH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
    }

    private fun adb() = AdbBridge.get(this)

    private fun launchApp(component: String) = shellQ.execute {
        try {
            // Many apps refuse secondary displays unless "force resizable" is on. Remember the old value to restore it.
            oldResizable = adb().shell("settings get global force_resizable_activities").trim().let { if (it.isEmpty() || it == "null") "0" else it }
            adb().shell("settings put global force_resizable_activities 1")
            adb().shell("am start --display $displayId -n $component")
        } catch (_: Exception) {}
    }

    // TV -> phone input. Coordinates are 0..1 of the stream.
    private fun input(o: JSONObject) {
        if (mode == MODE_MIRROR) { InputService.instance?.handle(o); return }
        if (displayId < 0 || queued.get() > 6) return // drop when the shell can't keep up
        val w = streamW; val h = streamH
        val cmd = when (o.optString("t")) {
            "tap" -> "input -d $displayId tap ${(o.getDouble("x") * w).toInt()} ${(o.getDouble("y") * h).toInt()}"
            "swipe" -> "input -d $displayId swipe ${(o.getDouble("x1") * w).toInt()} ${(o.getDouble("y1") * h).toInt()} " +
                "${(o.getDouble("x2") * w).toInt()} ${(o.getDouble("y2") * h).toInt()} ${o.optInt("ms", 250).coerceIn(50, 1000)}"
            "key" -> when (o.optString("k")) {
                "back" -> "input -d $displayId keyevent 4"
                "home" -> "input -d $displayId keyevent 3"
                "recents" -> "input -d $displayId keyevent 187"
                else -> return
            }
            else -> return
        }
        queued.incrementAndGet()
        shellQ.execute { try { adb().shell(cmd) } catch (_: Exception) {} finally { queued.decrementAndGet() } }
    }

    private fun requestKey() {
        try { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) {}
    }

    private fun teardown() {
        stop = true
        if (mode == MODE_VIRTUAL && running) {
            val restore = oldResizable
            try { shellQ.execute { try { adb().shell("settings put global force_resizable_activities $restore") } catch (_: Exception) {} } } catch (_: Exception) {}
        }
        try { display?.release() } catch (_: Exception) {}
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { server?.stop() } catch (_: Exception) {}
        display = null; codec = null; projection = null; server = null; displayId = -1
        running = false
    }

    override fun onDestroy() { teardown(); super.onDestroy() }
}
