plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Overridden by the release workflow from the pushed tag; the defaults in
// gradle.properties are what a local build produces.
val appVersionName: String = providers.gradleProperty("mastertool.versionName").get()
val appVersionCode: Int = providers.gradleProperty("mastertool.versionCode").get().toInt()

android {
    namespace = "com.kaiharimoto.mastertool"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "com.kaiharimoto.mastertool"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            // Intentionally not a secret — see androidApp/keystore/README.md.
            // A stable signature is what makes the in-app updater possible at
            // all: Android rejects an update signed with a different key.
            storeFile = file(providers.gradleProperty("mastertool.storeFile").get())
            storePassword = providers.gradleProperty("mastertool.storePassword").get()
            keyAlias = providers.gradleProperty("mastertool.keyAlias").get()
            keyPassword = providers.gradleProperty("mastertool.keyPassword").get()
        }
    }

    buildTypes {
        release {
            // Left unminified for now: the app is personal-use and R8 rules for
            // SQLDelight plus kotlinx-serialization are not worth debugging for
            // a build nobody ships through a store.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Same key as release, so a debug build installed on the tablet can
            // still be replaced by a downloaded release without uninstalling.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        val java = JavaVersion.toVersion(libs.versions.jdk.get())
        sourceCompatibility = java
        targetCompatibility = java
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["androidTest"].java.srcDir("src/androidTest/kotlin")

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/{INDEX.LIST,DEPENDENCIES}",
        )
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    // The APK is Neue Master Tool (v1.3.0): its screens, and the builder state
    // and plumbing beneath them. The tablet's `:ui` is no longer in it.
    implementation(project(":neue"))
    implementation(project(":builder"))
    implementation(project(":core"))

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    // Each application picks its own SQL driver and HTTP engine; :core ships none.
    implementation(libs.sqldelight.driver.android)
    implementation(libs.ktor.client.okhttp)

    // The emulator smoke test (.github/workflows/android-smoke.yml): Neue opens on
    // a tablet, finds the deck the tablet app left, and is photographed doing it.
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
