pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}

providers.gradleProperty("wasmline.localPath").orNull?.let { includeBuild(it) }

dependencyResolutionManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "evilo-plugin-build-logic"
include(":base", ":plugin")
