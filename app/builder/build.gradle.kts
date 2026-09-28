plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * The part of the builder that is not a look: the deck's state holder
 * (`DeckBuilderState`), the app's dependencies, the updater's seam, the image
 * loader, the drag controller's value types, the shader seam and the card foil.
 *
 * Neue is built on exactly this and on `:core`, and nothing else of the tablet's
 * `:ui` — which is why it is its own module: Neue on Android must not carry the
 * tablet's Material screens and play stage. Files keep the package they had in
 * `:ui` (`com.kaiharimoto.mastertool.ui.*`), so moving them changed no import.
 */
kotlin {
    androidTarget()
    jvm("desktop")

    jvmToolchain(libs.versions.jdk.get().toInt())

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
    }
}

android {
    namespace = "com.kaiharimoto.mastertool.builder"
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
