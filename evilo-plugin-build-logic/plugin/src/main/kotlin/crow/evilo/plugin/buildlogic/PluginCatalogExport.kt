package crow.evilo.plugin.buildlogic

import java.time.Instant

internal fun createPluginCatalog(
    spec: Map<*, *>,
    packageBaseUrl: String,
    sourceRepositoryUrl: String,
    sourceSnapshotUrl: String?,
    sourceRevision: String,
    sourceArchiveName: String,
    sourceArchiveSha256: String,
    publishedAt: String,
): Map<String, Any?> {
    val descriptor = requireNotNull(spec["descriptor"] as? Map<*, *>) { "plugin-spec.json must contain a descriptor object." }
    val metadata = requireNotNull(spec["catalog"] as? Map<*, *>) { "plugin-spec.json must contain a catalog object." }
    val publication = parseCatalogDate(publishedAt)
    if (metadata.containsKey("createdAt")) {
        val createdAt = metadata["createdAt"]
        require(createdAt is String) { "Plugin creation date must be a date or timestamp." }
        require(parseCatalogDate(createdAt) <= publication) { "Publication date precedes plugin creation." }
    }
    val release = linkedMapOf<String, Any?>(
        "version" to requireNotNull(descriptor["version"]) { "The descriptor must declare its version." },
        "versionCode" to requireNotNull(descriptor["versionCode"]) { "The descriptor must declare its versionCode." },
        "manifestUrl" to "$packageBaseUrl/manifest.wlm",
        "sourceRevision" to sourceRevision,
        "sourceArchiveUrl" to "$packageBaseUrl/$sourceArchiveName",
        "sourceArchiveSha256" to sourceArchiveSha256,
        "publishedAt" to publication.toString(),
    )
    val releaseOptions = (spec["release"] as? Map<*, *>).orEmpty()
    require(releaseOptions.keys.all { it in setOf("minimumHostVersionCode", "maximumHostVersionCode") }) { "Unsupported source release option." }
    releaseOptions.forEach { (key, value) -> release[key.toString()] = value }
    val protocol = descriptor["protocol"] as? Map<*, *>
    val required = (protocol?.get("requiredCapabilities") as? List<*>).orEmpty()
    val minor = (protocol?.get("minimumMinor") as? Number)?.toLong() ?: 0L
    val major = (protocol?.get("major") as? Number)?.toLong() ?: 1L
    require((major == 1L && minor == 0L && required.all { it in setOf("media.catalog.v1", "http.request.v1", "storage.kv.v1") }) ||
        ((release["minimumHostVersionCode"] as? Number)?.toLong() ?: 1L) >= 2L) {
        "Required new capabilities must declare minimumHostVersionCode >= 2 to protect older hosts."
    }
    sourceSnapshotUrl?.let { release["sourceUrl"] = it }
    val plugin = metadata.entries.associate { it.key.toString() to it.value }.toMutableMap().apply {
        remove("certified")
        remove("version")
        remove("updatedAt")
        // Signed descriptor fields are declared once; the app expands them into each release.
        for (field in listOf("id", "name", "kinds", "languages")) {
            put(field, requireNotNull(descriptor[field]) { "The descriptor must declare its $field." })
        }
        if (descriptor.containsKey("protocol")) put("protocol", descriptor["protocol"]) else remove("protocol")
        // The descriptor holds no network or storage declaration; drop a legacy block.
        remove("permissions")
        put("sourceUrl", sourceRepositoryUrl)
        put("iconUrl", "$packageBaseUrl/icon.png")
        put("availableVersions", listOf(release))
    }
    return linkedMapOf("name" to "${descriptor["name"]} plugins", "plugins" to listOf(plugin))
}

private fun parseCatalogDate(value: String): Instant =
    Instant.parse(if (DATE.matches(value)) "${value}T00:00:00Z" else value)

private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
