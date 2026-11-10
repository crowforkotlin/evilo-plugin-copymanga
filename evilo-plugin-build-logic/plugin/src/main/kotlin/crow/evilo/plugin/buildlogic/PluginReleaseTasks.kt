package crow.evilo.plugin.buildlogic

import crow.wasmline.loader.model.WasmlineManifestLimits
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

fun guestDescriptorJson(spec: Map<*, *>): String {
    require(spec["descriptor"] is Map<*, *>) { "plugin.json must contain a descriptor object." }
    return JsonOutput.toJson(spec["descriptor"]).also {
        require(it.toByteArray(Charsets.UTF_8).size <= WasmlineManifestLimits.DEFAULT_MAX_STRING_BYTES) {
            "The guest descriptor exceeds Wasmline's signed metadata string limit."
        }
    }
}

abstract class GenerateGuestDescriptor : DefaultTask() {
    @get:InputFile abstract val inputSpec: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val spec = JsonSlurper().parse(inputSpec.get().asFile) as Map<*, *>
        val json = guestDescriptorJson(spec)
        val literal = "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        val file = outputDirectory.file("crow/evilo/plugin/build/ReleaseDescriptor.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeText("package crow.evilo.plugin.build\n\n" +
            "import crow.evilo.plugin.api.PluginDescriptor\n" +
            "import crow.evilo.plugin.api.PluginJson\n\n" +
            "val releaseDescriptor: PluginDescriptor = PluginJson.decodeFromString($literal)\n")
    }
}

@DisableCachingByDefault(because = "The private authority key must not be cached or copied into build outputs.")
abstract class CheckPluginAuthority : DefaultTask() {
    @get:Input abstract val keyPath: Property<String>
    @get:Internal abstract val repositoryDirectory: DirectoryProperty

    @TaskAction
    fun check() {
        val file = File(keyPath.get()).toPath()
        require(Files.isRegularFile(file) && !Files.isSymbolicLink(file)) {
            "Missing authority key. Generate it with evilo's :evilo-app:generatePluginSigningKeys, not a template-local key generator."
        }
        require(!file.toRealPath().startsWith(repositoryDirectory.get().asFile.toPath().toRealPath())) { "Signing keys must stay outside the plugin repository." }
        val value = Files.readString(file).trim()
        require(Regex("[0-9a-fA-F]{64}").matches(value)) { "Invalid authority key format." }
        val publicKeyFile = file.parent.resolve("public.key")
        val metadataFile = file.parent.resolve("authority.properties")
        require(Files.isRegularFile(publicKeyFile) && Files.isRegularFile(metadataFile) &&
            !Files.isSymbolicLink(publicKeyFile) && !Files.isSymbolicLink(metadataFile)) { "The application authority is incomplete or uses symlinked files." }
        val publicHex = Files.readString(publicKeyFile).trim().lowercase()
        require(Regex("[0-9a-f]{64}").matches(publicHex)) { "Invalid authority public key format." }
        val metadata = Properties().apply { Files.newInputStream(metadataFile).use(::load) }
        require(metadata.getProperty("publicKeyHex") == publicHex &&
            !metadata.getProperty("keyId").isNullOrBlank()) { "Authority metadata does not match its public key." }
        val factory = KeyFactory.getInstance("Ed25519")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec("302e020100300506032b657004220420".hexToByteArray() + value.hexToByteArray()))
        val publicKey = factory.generatePublic(X509EncodedKeySpec("302a300506032b6570032100".hexToByteArray() + publicHex.hexToByteArray()))
        val probe = "evilo.plugin.authority".toByteArray(Charsets.US_ASCII)
        val signer = Signature.getInstance("Ed25519").apply { initSign(privateKey); update(probe) }
        val verifier = Signature.getInstance("Ed25519").apply { initVerify(publicKey); update(probe) }
        require(verifier.verify(signer.sign())) { "The application authority private/public keys do not match." }
    }
}

abstract class ExportPluginRelease : DefaultTask() {
    @get:InputFile abstract val inputSpec: RegularFileProperty
    @get:InputFile abstract val iconFile: RegularFileProperty
    @get:InputDirectory abstract val packageDirectory: DirectoryProperty
    @get:InputFile abstract val sources: RegularFileProperty
    @get:Input abstract val packageBaseUrl: Property<String>
    @get:Input abstract val sourceRepositoryUrl: Property<String>
    @get:Input abstract val sourceSnapshotUrl: Property<String>
    @get:Input abstract val sourceRevision: Property<String>
    @get:Input abstract val publishedAt: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun export() {
        val base = checkedUrl(packageBaseUrl.get()).trimEnd('/')
        val repository = checkedUrl(sourceRepositoryUrl.get())
        val snapshot = checkedUrl(sourceSnapshotUrl.get())
        val revision = sourceRevision.get()
        require(Regex("[0-9a-f]{40}(?:[0-9a-f]{24})?").matches(revision)) { "Use a full immutable source commit." }
        val date = Instant.parse(publishedAt.get()).toString()
        val spec = JsonSlurper().parse(inputSpec.get().asFile) as Map<*, *>
        val descriptor = spec["descriptor"] as Map<*, *>
        val metadata = spec["catalog"] as Map<*, *>
        require(Instant.parse(metadata["createdAt"] as String) <= Instant.parse(date)) { "Publication date precedes plugin creation." }
        val output = outputDirectory.get().asFile
        val packageFiles = packageDirectory.get().asFile
        require(File(packageFiles, "manifest.wlm").isFile && File(packageFiles, "artifacts").isDirectory) { "The signed package is incomplete." }
        output.mkdirs()
        packageFiles.copyRecursively(output, overwrite = true)
        val archive = sources.get().asFile
        archive.copyTo(File(output, archive.name), overwrite = true)
        val icon = iconFile.get().asFile
        icon.copyTo(File(output, "icon.png"), overwrite = true)
        val release = linkedMapOf<String, Any?>(
            "version" to descriptor["version"], "versionCode" to descriptor["versionCode"],
            "kinds" to descriptor["kinds"], "languages" to descriptor["languages"],
            "manifestUrl" to "$base/manifest.wlm", "sourceUrl" to snapshot, "sourceRevision" to revision,
            "sourceArchiveUrl" to "$base/${archive.name}", "sourceArchiveSha256" to sha256(archive),
            "publishedAt" to date, "usable" to true, "minimumHostVersionCode" to 1,
            "protocol" to descriptor["protocol"], "permissions" to descriptor["permissions"],
        )
        val plugin = metadata.entries.associate { it.key.toString() to it.value }.toMutableMap().apply {
            put("id", descriptor["id"]); put("name", descriptor["name"]); put("version", descriptor["version"])
            put("languages", descriptor["languages"]); put("kinds", descriptor["kinds"])
            put("sourceUrl", repository); put("iconUrl", "$base/icon.png"); put("updatedAt", date)
            put("certified", false); put("availableVersions", listOf(release))
        }
        val catalog = linkedMapOf("schemaVersion" to 1, "id" to "${descriptor["id"]}.repository",
            "name" to "${descriptor["name"]} plugins", "description" to "Publisher-maintained plugins; certification does not guarantee safety.",
            "updatedAt" to date, "plugins" to listOf(plugin))
        File(output, "plugins.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(catalog)) + "\n")
    }

    private fun checkedUrl(value: String): String {
        require(value.startsWith("https://") && value.none { it <= ' ' || it in "\\#" }) { "Published releases and sources require HTTPS URLs." }
        val uri = java.net.URI(value)
        require(uri.host != null && uri.userInfo == null && uri.query == null) { "Invalid release URL." }
        require(listOf(".wlm", ".wasm", ".pwasm", ".cwasm").none(uri.path.lowercase()::endsWith)) { "Source links must not point to binary artifacts." }
        return value
    }
}
