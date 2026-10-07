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
// WebAssembly for the Lounge's browser table (`docs/LOUNGE.md`); `-Pmastertool.web=false` leaves it out, as in :core.
val webEnabled = providers.gradleProperty("mastertool.web").orNull?.toBooleanStrictOrNull() ?: true

kotlin {
    androidTarget()
    jvm("desktop")
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    if (webEnabled) {
        wasmJs { browser() }
    }

    jvmToolchain(libs.versions.jdk.get().toInt())

    sourceSets {
        // Skia's own API (the shader seam's runtime effects) on every target that draws with Skiko: the desktop and
        // the browser. Android draws with its own Skia behind android.graphics, so it keeps its own actual.
        val skikoMain by creating {
            dependsOn(commonMain.get())
        }
        named("desktopMain") { dependsOn(skikoMain) }
        if (webEnabled) {
            named("wasmJsMain") { dependsOn(skikoMain) }
        }
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
