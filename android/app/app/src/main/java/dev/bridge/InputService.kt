package dev.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

/** Mirror mode only: turns TV remote input into taps, swipes and Back/Home on the phone's real screen. */
class InputService : AccessibilityService() {
    companion object { @Volatile var instance: InputService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val p = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    fun handle(o: JSONObject) {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels; val h = dm.heightPixels
        when (o.optString("t")) {
            "tap" -> { val x = o.getDouble("x").toFloat() * w; val y = o.getDouble("y").toFloat() * h; gesture(x, y, x, y, 50) }
            "swipe" -> gesture(o.getDouble("x1").toFloat() * w, o.getDouble("y1").toFloat() * h,
                o.getDouble("x2").toFloat() * w, o.getDouble("y2").toFloat() * h, o.optLong("ms", 250).coerceIn(50, 1000))
            "key" -> when (o.optString("k")) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        }
    }
}
