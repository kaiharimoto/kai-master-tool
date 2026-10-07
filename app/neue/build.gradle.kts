import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Neue Master Tool, in Master UI: the app. Its own release track (`neue-v*`,
// release-neue.yml) and data directory; built on :core and :builder alone.
//
// Present's recording (1.1.13): the natives of FFmpeg and JavaCPP for the system this build runs on, and only
// those — release-neue.yml packages each installer on its own system (Windows x64, a Mac of each processor,
// Linux x64), so every installer carries one platform's ~21–31 MB and never the others'. -Pneue.javacppPlatform
// overrides it (e.g. `macosx-x86_64` for a cross-check).
val javacppPlatform: String = providers.gradleProperty("neue.javacppPlatform").orNull ?: run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase().let { if (it == "aarch64" || it == "arm64") "arm64" else "x86_64" }
    when {
        "mac" in os || "darwin" in os -> "macosx-$arch"
        "win" in os -> "windows-$arch"
        else -> "linux-$arch"
    }
}

// Two targets. `jvm` is the desktop app (packaged below); `android` is the same
// app as a library the APK (`:androidApp`) hosts. Almost all of it lives in
// `sharedMain`, a source set between the two: both targets are JVM, so the JDK
// (java.io, java.time, String.format) is there, and only what is truly one
// platform's — a window, an AWT dialog, Skia, an Intent — is in `jvmMain` or
// `androidMain`.
kotlin {
    jvm()
    androidTarget()
    jvmToolchain(libs.versions.jdk.get().toInt())

    sourceSets {
        val sharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(project(":builder"))
                implementation(project(":core"))
                // The table (duel, kit, theme, cards) is part of what Neue is: the studio and the APK see it through Neue.
                api(project(":table"))

                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.runtime)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor)
                implementation(libs.zxing.core)
                // Ai's Anthropic connection (1.0.43): the official SDK, on both targets.
                implementation(libs.anthropic.java)
            }
        }
        jvmMain {
            dependsOn(sharedMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.sqldelight.driver.jvm)
                implementation(libs.whisper.jni)
                // Present's recording (1.1.13): the camera, and the video rendered from a take. JavaCV alone, without
                // the ~1 GB its POM pulls (OpenCV, OpenBLAS, librealsense…); JavaCPP and the LGPL FFmpeg named here,
                // with this platform's natives. Never an `-gpl` classifier. Never in the APK: jvmMain is the desk's.
                implementation("${libs.javacv.get().module}:${libs.versions.javacv.get()}") { isTransitive = false }
                implementation(libs.javacpp)
                implementation(libs.ffmpeg)
                implementation("${libs.javacpp.get().module}:${libs.versions.javacpp.get()}:$javacppPlatform")
                implementation("${libs.ffmpeg.get().module}:${libs.versions.ffmpeg.get()}:$javacppPlatform")
            }
        }
        androidMain {
            dependsOn(sharedMain)
            dependencies {
                implementation(libs.sqldelight.driver.android)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // Ai's end-to-end test answers the card pool's network with a mock (1.0.43).
            implementation(libs.ktor.client.mock)
        }
    }
}

android {
    namespace = "com.kaiharimoto.neue"
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

// The fonts and the mark, for both targets (Compose resources, not the JVM
// classpath, which Android does not have).
compose.resources {
    publicResClass = false
    packageOfResClass = "com.kaiharimoto.neue.res"
    generateResClass = always
}

// One file, so the release workflow and a local build agree on what they are.
// The workflow overrides it with -Pneue.versionName.
val neueVersion: String = providers.gradleProperty("neue.versionName").orNull
    ?: file("VERSION").readText().trim()

compose.desktop {
    application {
        mainClass = "com.kaiharimoto.neue.MainKt"
        jvmArgs += listOf("-Dneue.version=$neueVersion", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            // FFmpeg's licence and how to replace it travel with every installer (1.1.13, the LGPL's terms):
            // `packaged/common/licenses/`, in the app's resources folder.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("packaged"))
            packageName = "NeueMasterTool"
            packageVersion = neueVersion
            description = "Neue Master Tool - a Yu-Gi-Oh! deck builder for the desktop"
            vendor = "kaiharimoto"
            copyright = "kaiharimoto"
            // SQLite over JDBC needs java.sql; the updater launches msiexec and
            // the file dialogs are Swing. Listing modules by hand is how an
            // installed build dies on a class the IDE run always had.
            // jdk.httpserver is Ai's own MCP server (1.0.43), which the Claude Code and
            // Codex CLIs reach the app's tools through, on 127.0.0.1 only.
            modules("java.sql", "java.naming", "java.desktop", "jdk.unsupported", "java.net.http", "jdk.crypto.ec", "jdk.httpserver")

            windows {
                // PERMANENT. Windows Installer recognises an upgrade by this
                // UUID; a build with a different one installs *beside* the old
                // one instead of replacing it. Never change it.
                upgradeUuid = "4E7A1C52-9B3D-4F0E-8A6B-2D51C0E3F7A9"
                // Per-user, so an update installs without an administrator
                // prompt and the in-app updater can run it unattended.
                perUserInstall = true
                menu = true
                menuGroup = "Neue Master Tool"
                shortcut = true
                dirChooser = false
                iconFile.set(project.file("icons/neue.ico"))
            }
            macOS {
                bundleID = "com.kaiharimoto.neue"
                iconFile.set(project.file("icons/neue.icns"))
                dockName = "Neue Master Tool"
                appCategory = "public.app-category.games"
                // Voice input (1.0.57): macOS asks the person before the microphone opens, in these words.
                // Recording a take (1.1.13): the camera, and the microphone's second use, in the same words.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Neue Master Tool listens while you speak to Ai, turning your words into text on this Mac, and records your voice when you record a take of a presentation.</string>
                        <key>NSCameraUsageDescription</key>
                        <string>Neue Master Tool shows your camera in a presentation's camera zone and records it when you record a take. The video stays on this Mac.</string>
                    """.trimIndent()
                }

                // The signing switch (Phase 4), off. release-neue.yml turns it on
                // only when the five Apple secrets are in the repository — see
                // docs/NEUE.md §5 — by importing the certificate and setting
                // MAC_SIGN_IDENTITY. Without them this block adds nothing and the
                // .dmg is built exactly as it always was: unsigned, and opened the
                // first time with right-click → Open.
                val identity = providers.environmentVariable("MAC_SIGN_IDENTITY").orNull
                if (!identity.isNullOrBlank()) {
                    signing {
                        sign.set(true)
                        this.identity.set(identity)
                        providers.environmentVariable("MAC_SIGN_KEYCHAIN").orNull?.let { keychain.set(it) }
                    }
                    notarization {
                        appleID.set(providers.environmentVariable("APPLE_ID"))
                        password.set(providers.environmentVariable("APPLE_APP_PASSWORD"))
                        teamID.set(providers.environmentVariable("APPLE_TEAM_ID"))
                    }
                    entitlementsFile.set(project.file("macos/entitlements.plist"))
                    runtimeEntitlementsFile.set(project.file("macos/entitlements.plist"))
                }
            }
            linux {
                packageName = "neue-master-tool"
                iconFile.set(project.file("icons/neue.png"))
            }
        }
    }
}
