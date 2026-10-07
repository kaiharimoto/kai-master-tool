plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * The duel table and everything it draws with — Master UI's theme and kit, the family cursor, the card face
 * with its foil — in code every target compiles: the desktop and Android, as part of Neue, and the browser,
 * where friends join the Lounge's rooms (`docs/LOUNGE.md`). One drawing, so a friend in a browser sees the
 * table kai sees. Files keep their `com.kaiharimoto.neue.*` packages, so moving them here changed no import.
 *
 * Only what is truly one platform's is outside commonMain: Skia's own API in `skikoMain` (the desktop and the
 * browser draw with the same Skia), AWT and Swing in `jvmMain`, android.* in `androidMain`.
 */

// WebAssembly for the Lounge's browser table; `-Pmastertool.web=false` leaves it out, as in :core.
val webEnabled = providers.gradleProperty("mastertool.web").orNull?.toBooleanStrictOrNull() ?: true

kotlin {
    jvm()
    androidTarget()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    if (webEnabled) {
        wasmJs { browser() }
    }

    jvmToolchain(libs.versions.jdk.get().toInt())

    sourceSets {
        commonMain.dependencies {
            api(project(":builder"))
            api(project(":core"))

            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.runtime)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
        val skikoMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(skikoMain)
            dependencies {
                implementation(compose.desktop.common)
            }
        }
        if (webEnabled) {
            named("wasmJsMain") {
                dependsOn(skikoMain)
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
        }
    }
}

android {
    namespace = "com.kaiharimoto.neue.table"
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

// The table's fonts and the card back. Public, because Neue's slides and PDFs read the same fonts.
compose.resources {
    publicResClass = true
    packageOfResClass = "com.kaiharimoto.table.res"
    generateResClass = always
}
