plugins { `kotlin-dsl` }

kotlin { jvmToolchain(libs.versions.jvm.get().toInt()) }

dependencies {
    implementation(project(":base"))
    implementation(libs.gradle.kotlin)
    implementation(libs.gradle.kotlinSerialization)
    implementation(libs.gradle.wasmline)
    implementation(libs.wasmline.loader)
    testImplementation(libs.kotlin.test.junit)
}
