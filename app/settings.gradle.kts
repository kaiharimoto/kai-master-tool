rootProject.name = "kai-master-tool"

pluginManagement {
    // Single source of truth for *plugin* versions. They live here rather than in
    // libs.versions.toml because several of them (the Compose compiler, the
    // Kotlin Android plugin) ship inside the Kotlin Gradle Plugin jar. Declaring
    // those with a version in a module while the KGP is already on the build
    // classpath is rejected by Gradle, so versions are pinned once here and every
    // module requests them with a bare `id("...")`.
    val kotlinVersion = "2.4.10"
    val composeVersion = "1.11.1"
    val agpVersion = "8.13.0"
    val sqldelightVersion = "2.3.2"

    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }

    plugins {
        id("org.jetbrains.kotlin.multiplatform") version kotlinVersion
        id("org.jetbrains.kotlin.android") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.serialization") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.compose") version kotlinVersion
        id("org.jetbrains.compose") version composeVersion
        id("com.android.application") version agpVersion
        id("com.android.library") version agpVersion
        id("app.cash.sqldelight") version sqldelightVersion
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Android / Compose toggle
//
// AGP, every androidx artifact and the SDK itself are served only from Google's
// Maven. Where that is unreachable the Compose and Android modules are skipped
// so `:core` still compiles and its tests still run. `:core` itself never needs
// them: it targets the JVM, which Android consumes through Kotlin's standard
// jvm -> androidJvm compatibility rule.
//
// Force either way with -Pmastertool.android=true|false.
// ---------------------------------------------------------------------------
val androidSdkDetected: Boolean =
    !System.getenv("ANDROID_HOME").isNullOrBlank() ||
        !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
        file("local.properties").takeIf { it.isFile }
            ?.readLines()
            ?.any { it.trimStart().startsWith("sdk.dir") } == true

val androidEnabled: Boolean =
    providers.gradleProperty("mastertool.android").orNull?.toBooleanStrictOrNull()
        ?: androidSdkDetected

// Compose Multiplatform pulls androidx artifacts on every target, the desktop
// one included, so all three UI-bearing modules stand or fall together with
// Google's Maven being reachable.
include(":core")

if (androidEnabled) {
    include(":builder")
    // The duel table and what it draws with, for Neue and for the Lounge's browser table (docs/LOUNGE.md).
    include(":table")
    // The Lounge's browser app, compiled to WebAssembly (docs/LOUNGE.md); -Pmastertool.web=false leaves it out.
    if (providers.gradleProperty("mastertool.web").orNull?.toBooleanStrictOrNull() != false) include(":guest")
    include(":androidApp")
    // Neue Master Tool: the app, on the desktop and (hosted by :androidApp) Android.
    include(":neue")
}

// ---------------------------------------------------------------------------
// The studio
//
// A headless renderer that draws the play stage to PNG files, so a change to
// the light or the geometry can be *looked at* rather than only asserted. It is
// a development instrument and ships in nothing, so it stays off by default:
// CI builds exactly the three modules it built before this existed, and the
// studio is opted into with -Pmastertool.studio=true.
//
// It needs :ui, so it is gated on the same Google Maven reachability.
// ---------------------------------------------------------------------------
val studioEnabled: Boolean =
    providers.gradleProperty("mastertool.studio").orNull?.toBooleanStrictOrNull() ?: false

if (androidEnabled && studioEnabled) {
    include(":studio")
}

if (!androidEnabled) {
    logger.lifecycle(
        "[kai's master tool] Compose/Android modules skipped (no Android SDK and no Google Maven access). " +
            "Only :core is configured. Override with -Pmastertool.android=true."
    )
}
