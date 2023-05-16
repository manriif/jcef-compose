plugins {
    org.jetbrains.kotlin.multiplatform
    org.jetbrains.compose
}

group = packageGroup
version = packageVersion

kotlin {
    jvm {
        jvmToolchain(11)
        withJava()
    }

    sourceSets.named("jvmMain") {
        dependencies {
            implementation(compose.desktop.common)
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.ui)
        }
    }
}