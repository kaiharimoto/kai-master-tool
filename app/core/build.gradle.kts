plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.sqldelight")
}

// iOS needs the Kotlin/Native toolchain (a large download) and is only useful on
// a Mac, so it stays opt-in until there's an Xcode host to build it on.
val iosEnabled =
    providers.gradleProperty("mastertool.ios").orNull?.toBooleanStrictOrNull()
        ?: System.getProperty("os.name").startsWith("Mac")

// WebAssembly for the Lounge's browser table. On by default; `-Pmastertool.web=false` leaves it out.
val webEnabled = providers.gradleProperty("mastertool.web").orNull?.toBooleanStrictOrNull() ?: true

// The app's registration with Google, for Drive sync (1.0.69): put in at build time from the release
// workflows' secrets (GOOGLE_OAUTH_CLIENT_ID, GOOGLE_OAUTH_CLIENT_SECRET), never kept in the repository.
// Left blank — a local build, CI — and Google Drive is simply not offered.
val cloudKeys = tasks.register("generateCloudKeys") {
    val clean = { raw: String -> raw.filter { it.isLetterOrDigit() || it in "._-" } }
    val id = providers.environmentVariable("GOOGLE_OAUTH_CLIENT_ID").orElse("").map(clean)
    val secret = providers.environmentVariable("GOOGLE_OAUTH_CLIENT_SECRET").orElse("").map(clean)
    val out = layout.buildDirectory.dir("generated/cloudkeys/kotlin")
    inputs.property("id", id)
    inputs.property("secret", secret)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/kaiharimoto/mastertool/core/sync/CloudKeys.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.kaiharimoto.mastertool.core.sync\n\n" +
                "/** Written by the build from the release secrets; never in the repository. */\n" +
                "internal object CloudKeys {\n" +
                "    const val GOOGLE_ID = \"${id.get()}\"\n" +
                "    const val GOOGLE_SECRET = \"${secret.get()}\"\n" +
                "}\n",
        )
    }
}

kotlin {
    // Deliberately no androidTarget(). Nothing here touches an Android API, and
    // Kotlin's jvm -> androidJvm compatibility rule lets the Android app consume
    // this module's JVM variant directly. That keeps :core free of the Android
    // Gradle Plugin, so the domain layer builds and tests anywhere.
    jvm()

    if (iosEnabled) {
        iosArm64()
        iosSimulatorArm64()
    }

    // The browser (the Lounge, `docs/LOUNGE.md`): the duel table compiled to WebAssembly for friends who join
    // from a browser. The rules stay one copy: the guest's page runs this module, as the desk and Android do.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    if (webEnabled) {
        wasmJs {
            browser()
            nodejs()
        }
    }

    jvmToolchain(libs.versions.jdk.get().toInt())

    sourceSets {
        commonMain {
            kotlin.srcDir(cloudKeys)
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // `api`, not `implementation`: JsonObject appears in this module's
            // public surface (the preserved #ydkx-extended payload), so callers
            // must be able to see the type.
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.json)
            api(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        // Ai World's scripts (1.0.97): the engine is plain Java, so it lives on the JVM side, which the APK shares.
        jvmMain.dependencies {
            implementation(libs.rhino)
        }

        // HTTP engines and SQL drivers are chosen by each application, not here,
        // so a JDBC driver never ends up inside the APK.
        jvmTest.dependencies {
            implementation(libs.sqldelight.driver.jvm)
        }
    }
}

sqldelight {
    databases {
        create("MasterToolDatabase") {
            packageName.set("com.kaiharimoto.mastertool.core.db")
            // `verifyMigrations` is deliberately left off: it requires a checked-in
            // .db snapshot of each old schema, and this plugin version registers no
            // task to produce one. `MigrationTest` makes the same assertion from
            // the test source set instead — it upgrades a real version 1 database
            // and compares the result against a freshly created one — which has the
            // advantage of running in the :core test job on every push.
        }
    }
}
