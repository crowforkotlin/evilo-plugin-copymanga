package crow.evilo.plugin.copymanga

import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/**
 * Produces the CopyManga client identity and the per-request authentication headers.
 *
 * CopyManga gates every `/api/v3` call on a `platform`/`version` pair and ignores the
 * signature today, but the official client always sends it and the server may start
 * enforcing it. The values below are the reviewed contract from the interface notes:
 * `platform` must be `3`, and `version` must stay inside the `3.0.0`–`3.1.1` window
 * (outside it the API answers `code: 210` with an upgrade prompt).
 */
internal object CopyMangaSigner {
    /** Client version sent as the `version` header and inside the user agent. */
    const val CLIENT_VERSION = "3.1.1"

    /** Client platform identifier; `1` and `2` are rejected with `code: 210`. */
    const val PLATFORM = "3"

    /** Catalog client identity; page images use the same non-empty UA required by the CDN. */
    const val USER_AGENT = "COPY/$CLIENT_VERSION"

    /** The content mirrors use the browser identity used by kira's ApiTransport. */
    const val MIRROR_USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.200 Mobile Safari/537.36"

    const val REQUESTED_WITH = "com.manga2020.app"

    /** MD5 of the fixed protocol key, sent by the official client. */
    const val UMSTRING = "dc1f0963804339e44aa381177cf4f4fd"

    /** Client channel marker sent as the `source` header. */
    const val SOURCE = "copyApp"

    /** Referer identifying the calling client; the JSON API does not validate it. */
    const val REFERER = "com.copymanga.app-$CLIENT_VERSION"

    /** Image format selector; `1` evaluates to the WebP profiles served by the CDN. */
    const val WEBP = "1"

    // CopyManga's client protocol key is unrelated to the package signing key.
    private const val CLIENT_KEY = "ggmxzqkjjrwmbptastlslb" + "2025.05.22"

    /**
     * Returns the CopyManga authentication headers for [timestampSeconds].
     *
     * The timestamp is sent twice (`X-Auth-Timestamp` and `tapString`) and signed as
     * HMAC-SHA256 over its decimal string with the fixed client key.
     */
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
