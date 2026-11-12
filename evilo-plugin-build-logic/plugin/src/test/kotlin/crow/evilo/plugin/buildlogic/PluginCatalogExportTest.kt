package crow.evilo.plugin.buildlogic

import groovy.json.JsonSlurper
import java.time.format.DateTimeParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PluginCatalogExportTest {
    @Test
    fun compactCatalogDeclaresSignedFieldsOnceWithoutChangingTheDescriptor() {
        val spec = pluginSpec()
        val signedDescriptor = guestDescriptorJson(spec)
        val descriptor = spec["descriptor"] as Map<*, *>
        val generated = catalog(spec)
        val plugin = generated.plugin()
        val release = plugin.release()
        assertEquals(setOf("name", "plugins"), generated.keys)
        for (field in listOf("id", "name", "kinds", "languages", "protocol", "permissions")) {
            assertEquals(descriptor[field], plugin[field])
        }
        for (field in listOf("kinds", "languages", "protocol", "permissions", "sourceUrl", "usable", "minimumHostVersionCode")) {
            assertFalse(release.containsKey(field))
        }
        for (field in listOf("version", "updatedAt", "certified")) assertFalse(plugin.containsKey(field))
        assertEquals("https://code.example.com/reader", plugin["sourceUrl"])
        assertEquals("https://plugins.example.com/reader/1.0.0/manifest.wlm", release["manifestUrl"])
        assertEquals("a".repeat(40), release["sourceRevision"])
        assertEquals("https://plugins.example.com/reader/1.0.0/sources.zip", release["sourceArchiveUrl"])
        assertEquals("b".repeat(64), release["sourceArchiveSha256"])
        assertEquals("1.0.0", release["version"])
        assertEquals(1, release["versionCode"])
        assertEquals(listOf("Example author"), plugin["authors"])
        assertEquals(signedDescriptor, guestDescriptorJson(spec))
    }

    @Test
    fun explicitSnapshotAndPublicationTimestampRemainReleaseSpecific() {
        val snapshot = "https://code.example.com/reader/tree/${"a".repeat(40)}"
        val publishedAt = "2026-11-10T03:40:15Z"
        val release = catalog(snapshot = snapshot, publishedAt = publishedAt).plugin().release()
        assertEquals(snapshot, release["sourceUrl"])
        assertEquals(publishedAt, release["publishedAt"])
    }

    @Test
    fun ordinaryDatesAndOmittedCreationDatesAreSupportedWithoutUsingTheClock() {
        val generated = catalog(publishedAt = "2026-11-10")
        assertEquals("2026-11-09", generated.plugin()["createdAt"])
        assertEquals("2026-11-10T00:00:00Z", generated.plugin().release()["publishedAt"])
        val spec = pluginSpec()
        val metadata = spec["catalog"] as Map<*, *>
        val withoutCreation = catalog(spec + ("catalog" to (metadata - "createdAt")), publishedAt = "2026-11-10")
        assertFalse(withoutCreation.plugin().containsKey("createdAt"))
        assertEquals("2026-11-10T00:00:00Z", withoutCreation.plugin().release()["publishedAt"])
    }

    @Test
    fun legacyReviewFieldsCannotReplaceSignedDescriptorFieldsOrDefaults() {
        val spec = pluginSpec()
        val descriptor = spec["descriptor"] as Map<*, *>
        val metadata = spec["catalog"] as Map<*, *>
        val overridden = spec + ("catalog" to (metadata + mapOf(
            "id" to "different.plugin", "name" to "Different name", "version" to "9.0.0",
            "certified" to true, "permissions" to emptyMap<String, Any>(), "protocol" to emptyMap<String, Any>(),
        )))
        val plugin = catalog(overridden).plugin()
        assertEquals(descriptor["id"], plugin["id"])
        assertEquals(descriptor["name"], plugin["name"])
        assertEquals(descriptor["permissions"], plugin["permissions"])
        assertEquals(descriptor["protocol"], plugin["protocol"])
        assertFalse(plugin.containsKey("certified"))
        val defaults = catalog(overridden + ("descriptor" to (descriptor - setOf("permissions", "protocol")))).plugin()
        assertFalse(defaults.containsKey("permissions"))
        assertFalse(defaults.containsKey("protocol"))
    }

    @Test
    fun invalidDatesAndMissingSpecObjectsAreRejected() {
        assertFailsWith<IllegalArgumentException> { catalog(publishedAt = "2026-11-08") }
        assertFailsWith<DateTimeParseException> { catalog(publishedAt = "2026-02-30") }
        val spec = pluginSpec()
        assertFailsWith<IllegalArgumentException> { catalog(spec - "descriptor") }
        assertFailsWith<IllegalArgumentException> { catalog(spec - "catalog") }
        val metadata = spec["catalog"] as Map<*, *>
        assertFailsWith<IllegalArgumentException> { catalog(spec + ("catalog" to (metadata + ("createdAt" to null)))) }
    }

    private fun catalog(
        spec: Map<*, *> = pluginSpec(),
        snapshot: String? = null,
        publishedAt: String = "2026-11-10T03:40:15Z",
    ): Map<String, Any?> = createPluginCatalog(
        spec = spec,
        packageBaseUrl = "https://plugins.example.com/reader/1.0.0",
        sourceRepositoryUrl = "https://code.example.com/reader",
        sourceSnapshotUrl = snapshot,
        sourceRevision = "a".repeat(40),
        sourceArchiveName = "sources.zip",
        sourceArchiveSha256 = "b".repeat(64),
        publishedAt = publishedAt,
    )

    private fun Map<*, *>.plugin(): Map<*, *> = (this["plugins"] as List<*>).single() as Map<*, *>
    private fun Map<*, *>.release(): Map<*, *> = (this["availableVersions"] as List<*>).single() as Map<*, *>

    private fun pluginSpec(): Map<*, *> = JsonSlurper().parseText("""
        {
          "descriptor": {
            "id": "example.reader", "name": "Example reader", "version": "1.0.0", "versionCode": 1,
            "kinds": ["novel"], "languages": ["en"],
            "protocol": {"major": 1, "minimumMinor": 0,
              "requiredCapabilities": ["media.catalog.v1", "http.request.v1", "storage.kv.v1"], "optionalCapabilities": []},
            "permissions": {
              "api": {"origins": ["https://api.example.com"], "pathPrefixes": ["/content/"], "methods": ["GET"], "headers": ["accept"]},
              "media": {"origins": [], "pathPrefixes": ["/"], "methods": ["GET"], "headers": []}, "storage": true
            }
          },
          "catalog": {"authors": ["Example author"], "description": "Catalog export fixture.", "createdAt": "2026-11-09"}
        }
    """.trimIndent()) as Map<*, *>
}
