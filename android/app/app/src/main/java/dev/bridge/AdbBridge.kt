package dev.bridge

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * The app talks to this phone's own adbd over Wi-Fi (Android 11+ "Wireless debugging").
 * One-time pairing with the 6-digit code, then connect with a port found over mDNS.
 * The identity (key + certificate) is created once and stored in the app's private files.
 *
 * NOTE: written against libadb-android 3.x (AbsAdbConnectionManager: pair/connect/autoConnect/openStream).
 * Check signatures against the version Gradle resolves; adjust here only.
 */
class AdbBridge private constructor(private val dir: File) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val kf = File(dir, "adb_key.pk8"); val cf = File(dir, "adb_cert.der")
        if (kf.exists() && cf.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
            cert = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream())
        } else {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=Bridge")
            val now = System.currentTimeMillis()
            val b = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 60_000),
                Date(now + 3650L * 86_400_000), name, kp.public)
            val c = JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder("SHA512withRSA").build(kp.private)))
            kf.writeBytes(kp.private.encoded); cf.writeBytes(c.encoded)
            key = kp.private; cert = c
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "Bridge"

    /** Run a shell command on the phone as the shell user; returns its output. */
    fun shell(cmd: String): String {
        val s = openStream("shell:$cmd")
        return try { s.openInputStream().readBytes().toString(Charsets.UTF_8) } finally { try { s.close() } catch (_: Exception) {} }
    }

    companion object {
        @Volatile private var inst: AdbBridge? = null
        fun get(ctx: Context): AdbBridge = inst ?: synchronized(this) { inst ?: AdbBridge(ctx.filesDir).also { inst = it } }
    }
}
