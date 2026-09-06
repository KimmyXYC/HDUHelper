package moe.nepnep.hduhelper.data.campuscode

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.bouncycastle.asn1.gm.GMNamedCurves
import org.bouncycastle.crypto.digests.SM3Digest

/** Compatibility with the public uias-h5 HTTP interceptor, not credential storage crypto. */
internal object YmtSigner {
    // Public protocol constants shipped to every visitor in app.becf91ff.js / uias-app.9e4804be.js.
    private val protocolKey = BigInteger("e04d85ea0b237f2dfca75aa073978263227b48a0ee742f40f60cc89c6b5f6eee", 16)
    private val curve = GMNamedCurves.getByName("sm2p256v1")
    private val random = SecureRandom()
    private val utc = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)

    fun headers(path: String, params: Map<String, String>, now: Long): Map<String, String> {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val nonce = buildString { repeat(12) { append(alphabet[random.nextInt(alphabet.length)]) } }
        return mapOf("nonce" to nonce, "timestamp" to now.toString(), "charset" to "utf-8",
            "Sign" to sign(canonical(path, params, nonce, now)))
    }

    internal fun canonical(path: String, params: Map<String, String>, nonce: String, now: Long): String {
        val sorted = params.filterValues { it.isNotEmpty() }.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val digest = if (sorted.isEmpty()) "" else md5(sorted)
        return "GET\n$path\n$digest\n$nonce\n${utc.format(Instant.ofEpochMilli(now))}"
    }

    // sm-crypto doSignature(string) treats the SM3 hex output as UTF-8, with hash=false and der=false.
    // Standard SM3withSM2 would add ZA and hash again, producing an incompatible signature.
    internal fun sign(message: String, fixedK: BigInteger? = null): String {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val digest = SM3Digest().run { update(bytes, 0, bytes.size); ByteArray(digestSize).also { doFinal(it, 0) } }
        val e = BigInteger(1, hex(digest).toByteArray(Charsets.UTF_8))
        val n = curve.n
        while (true) {
            val k = fixedK ?: generateSequence { BigInteger(n.bitLength(), random) }.first { it.signum() > 0 && it < n }
            val x = curve.g.multiply(k).normalize().affineXCoord.toBigInteger()
            val r = e.add(x).mod(n)
            val s = protocolKey.add(BigInteger.ONE).modInverse(n).multiply(k.subtract(r.multiply(protocolKey))).mod(n)
            if (r.signum() != 0 && r.add(k) != n && s.signum() != 0) {
                return r.toString(16).padStart(64, '0') + s.toString(16).padStart(64, '0')
            }
            require(fixedK == null) { "Invalid test nonce" }
        }
    }

    fun qrParameters(userId: String, now: Long): Map<String, String> {
        // Date.parse(new Date()) in the school page truncates milliseconds.
        val timestamp = now / 1000 * 1000
        return linkedMapOf("sign" to md5("$userId|$timestamp|ok15we1@oid8x5afd@"), "timestamp" to timestamp.toString(), "userId" to userId)
    }

    @android.annotation.SuppressLint("WeakHash")
    internal fun md5(value: String) = hex(MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
