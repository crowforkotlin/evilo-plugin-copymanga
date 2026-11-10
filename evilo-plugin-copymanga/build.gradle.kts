plugins { id("evilo.plugin.guest") }

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(projects.eviloPluginCopymangaCore)
    }
}
