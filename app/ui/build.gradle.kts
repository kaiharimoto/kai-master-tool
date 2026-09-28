plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    // Compose UI has a genuinely different implementation per platform, so this
    // module — unlike :core — really does need an Android variant.
    androidTarget()
    jvm("desktop")

    jvmToolchain(libs.versions.jdk.get().toInt())

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":builder"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            // Back again, and used this time: the chibi logo is bundled art that
            // has to load identically on a tablet and on a desktop.
            implementation(compose.components.resources)

            implementation(libs.kotlinx.coroutines.core)
            // Used directly: the YDKX payload is carried through the UI untouched.
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
    }
}

// Named explicitly rather than derived, so the generated accessors land somewhere
// predictable and the import does not move when the module's namespace does.
compose.resources {
    publicResClass = true
    packageOfResClass = "com.kaiharimoto.mastertool.ui.art"
    generateResClass = auto
}

android {
    namespace = "com.kaiharimoto.mastertool.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        val java = JavaVersion.toVersion(libs.versions.jdk.get())
        sourceCompatibility = java
        targetCompatibility = java
    }
}
