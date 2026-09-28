import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Neue Master Tool: the desktop builder, in Master UI. A separate application
// with its own release track (`neue-v*`, see release-neue.yml) and its own
// data directory; it borrows :ui's state holders and nothing of its look.
kotlin {
    jvm()
    jvmToolchain(libs.versions.jdk.get().toInt())

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":builder"))
            implementation(project(":core"))

            implementation(compose.desktop.currentOs)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.driver.jvm)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
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
            packageName = "NeueMasterTool"
            packageVersion = neueVersion
            description = "Neue Master Tool - a Yu-Gi-Oh! deck builder for the desktop"
            vendor = "kaiharimoto"
            copyright = "kaiharimoto"
            // SQLite over JDBC needs java.sql; the updater launches msiexec and
            // the file dialogs are Swing. Listing modules by hand is how an
            // installed build dies on a class the IDE run always had.
            modules("java.sql", "java.naming", "java.desktop", "jdk.unsupported", "java.net.http", "jdk.crypto.ec")

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
            }
            linux {
                packageName = "neue-master-tool"
                iconFile.set(project.file("icons/neue.png"))
            }
        }
    }
}
