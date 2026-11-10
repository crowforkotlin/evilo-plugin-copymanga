package crow.evilo.plugin.copymanga

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CopyMangaSignerTest {
    @Test
    fun portableSignerMatchesJvmHmacSha256() {
        for (seconds in listOf(0L, 1_700_000_000L, 1_800_000_001L)) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec("ggmxzqkjjrwmbptastlslb2025.05.22".encodeToByteArray(), "HmacSHA256"))
            val expected = mac.doFinal(seconds.toString().encodeToByteArray()).toHexString()
            val headers = CopyMangaSigner.authHeaders(seconds)
            assertEquals(expected, headers["X-Auth-Signature"])
            assertEquals(seconds.toString(), headers["X-Auth-Timestamp"])
            assertEquals(seconds.toString(), headers["tapString"])
        }
    }

    @Test
    fun negativeTimestampsAreRejected() {
        assertFailsWith<IllegalArgumentException> { CopyMangaSigner.authHeaders(-1) }
    }
}
