// The plugins, loaded once, here.
//
// Every module requests its plugins with a bare `id("...")`; their jars are put on this script's classpath so
// that one classloader holds them for the whole build. That became a requirement with the Lounge's browser
// table (`docs/LOUNGE.md`): two modules compiling for WebAssembly each apply the Kotlin plugin's Node.js root
// plugin to this project, and a Kotlin plugin loaded per subproject is refused outright there, where before it
// was only a warning.
//
// Two things decide the shape:
//
//  - The Android and Compose plugins live only on Google's Maven. Naming them unconditionally (a `plugins`
//    block with `apply false` still resolves the artifact) would make this script — and therefore :core —
//    unbuildable wherever that is unreachable. So they are added only when the Android modules are, by the
//    same test settings.gradle.kts uses.
//  - The Kotlin plugin has to see AGP's classes (its Android diagnostics fail with NoClassDefFoundError
//    com/android/build/gradle/BaseExtension otherwise), so when AGP is on the build it is here beside it,
//    never in a module's own classloader below.
//
// The versions are settings.gradle.kts's `pluginManagement` ones: keep the two in step.
buildscript {
    val androidSdkDetected: Boolean =
        !System.getenv("ANDROID_HOME").isNullOrBlank() ||
            !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
            file("local.properties").takeIf { it.isFile }
                ?.readLines()
                ?.any { it.trimStart().startsWith("sdk.dir") } == true
    val androidEnabled: Boolean =
        (project.findProperty("mastertool.android") as String?)?.toBooleanStrictOrNull() ?: androidSdkDetected

    repositories {
        gradlePluginPortal()
        mavenCentral()
        if (androidEnabled) {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
        }
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.4.10")
        classpath("app.cash.sqldelight:gradle-plugin:2.3.2")
        if (androidEnabled) {
            classpath("com.android.tools.build:gradle:8.13.0")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.10")
            classpath("org.jetbrains.compose:compose-gradle-plugin:1.11.1")
        }
    }
}
