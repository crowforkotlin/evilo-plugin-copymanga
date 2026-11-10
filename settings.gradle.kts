pluginManagement {
    includeBuild("evilo-plugin-build-logic")
    providers.gradleProperty("wasmline.localPath").orNull?.let { includeBuild(it) }
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

rootProject.name = "evilo-plugin-copymanga-root"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
providers.gradleProperty("evilo.sdkPath").orNull?.let { includeBuild(it) }
providers.gradleProperty("wasmline.localPath").orNull?.let { includeBuild(it) }

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal { content { includeGroup("io.github.crowforkotlin.evilo") } }
    }
}

include(":evilo-plugin-copymanga-core", ":evilo-plugin-copymanga")
