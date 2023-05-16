plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(libs.compose.gradle)
    implementation(libs.kotlin.gradle)
    implementation(libs.idea.ext.gradle)
}