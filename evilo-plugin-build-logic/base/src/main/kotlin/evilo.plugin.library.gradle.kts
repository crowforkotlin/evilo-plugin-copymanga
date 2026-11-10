import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Kotlin DSL accessor generation applies this plugin without a consumer version catalog.
val catalog = extensions.getByType<VersionCatalogsExtension>().find("libs").orElse(null)
if (catalog != null) {
    kotlin {
        jvmToolchain(catalog.findVersion("jvm").get().requiredVersion.toInt())
        jvm()
        @OptIn(ExperimentalWasmDsl::class)
        wasmWasi { nodejs() }
        sourceSets.commonMain.dependencies { api(catalog.findLibrary("evilo-library").get()) }
        sourceSets.commonTest.dependencies { implementation(catalog.findLibrary("kotlin-test").get()) }
    }
}
