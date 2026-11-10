package crow.evilo.plugin.copymanga

import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

internal object CopyMangaSigner {
    const val CLIENT_VERSION = "2025.08.08"
    const val USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"

    // CopyManga's client protocol key is unrelated to the package signing key.
    private const val CLIENT_KEY = "ggmxzqkjjrwmbptastlslb" + "2025.05.22"

    fun authHeaders(timestampSeconds: Long): Map<String, String> {
        require(timestampSeconds >= 0) { "The authentication timestamp must not be negative." }
        val message = timestampSeconds.toString()
        val signature = HmacSHA256(CLIENT_KEY.encodeToByteArray())
            .doFinal(message.encodeToByteArray())
            .toHexString()
        return mapOf(
            "X-Auth-Timestamp" to message,
            "tapString" to message,
            "X-Auth-Signature" to signature,
        )
    }
}
