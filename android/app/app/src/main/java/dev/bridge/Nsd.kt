package dev.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** Finds the phone's own Wireless-debugging endpoints over mDNS. The ports change every time it is toggled. */
object Nsd {
    const val CONNECT = "_adb-tls-connect._tcp"
    const val PAIRING = "_adb-tls-pairing._tcp"

    /** Calls onFound(host, port) for the first service of this type that resolves, then stops. */
    @Suppress("DEPRECATION")
    fun find(ctx: Context, type: String, onFound: (String, Int) -> Unit, onTimeout: () -> Unit, timeoutMs: Long = 8000) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        var done = false
        lateinit var listener: NsdManager.DiscoveryListener
        fun finish() { if (!done) { done = true; try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {} } }
        listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) {
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        if (done) return
                        val host = r.host?.hostAddress ?: return
                        finish(); onFound(host, r.port)
                    }
                    override fun onResolveFailed(s: NsdServiceInfo, code: Int) {}
                })
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { finish(); onTimeout() }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (!done) { finish(); onTimeout() } }, timeoutMs)
    }
}
