plugins { id("evilo.plugin.library") }

kotlin {
    sourceSets.commonMain.dependencies { implementation(libs.kotlincrypto.hmac.sha2) }
}
