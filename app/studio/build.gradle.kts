plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm()
    jvmToolchain(libs.versions.jdk.get().toInt())

    sourceSets {
        // Synthesising a key event uses the constructor Compose reserves for its
        // own modules. Opted into here and nowhere else: the studio is allowed to
        // reach for an unstable API because it ships to nobody and a broken one
        // fails at the next build, where :ui would fail on somebody's tablet.
        all { languageSettings.optIn("androidx.compose.ui.InternalComposeUiApi") }

        jvmMain.dependencies {
            implementation(project(":ui"))
            implementation(project(":core"))
            // Neue Master Tool, the desktop builder, is photographed by `shootNeue`.
            implementation(project(":neue"))
            implementation(libs.coil.compose)

            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.sqldelight.driver.jvm)
            implementation(libs.ktor.client.okhttp)
        }
    }
}

tasks.register<JavaExec>("spikeShader") {
    group = "verification"
    description = "Asks whether a runtime shader compiles and rasters with no GPU."
    dependsOn("jvmMainClasses")
    mainClass.set("com.kaiharimoto.mastertool.studio.ShaderSpikeKt")
    classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles +
        kotlin.jvm().compilations.getByName("main").output.allOutputs
    workingDir = rootProject.projectDir
    jvmArgs("-Djava.awt.headless=true", "-Dskiko.renderApi=SOFTWARE")
    argumentProviders.add(
        CommandLineArgumentProvider {
            providers.gradleProperty("shot.args").orNull?.split(" ")?.filter { it.isNotBlank() }
                ?: emptyList()
        }
    )
}

tasks.register<JavaExec>("spikeSeam") {
    group = "verification"
    description = "Asks the three questions docs/PHOTOREAL.md's widened seam rests on."
    dependsOn("jvmMainClasses")
    mainClass.set("com.kaiharimoto.mastertool.studio.SeamSpikeKt")
    classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles +
        kotlin.jvm().compilations.getByName("main").output.allOutputs
    workingDir = rootProject.projectDir
    jvmArgs("-Djava.awt.headless=true", "-Dskiko.renderApi=SOFTWARE")
    argumentProviders.add(
        CommandLineArgumentProvider {
            providers.gradleProperty("shot.args").orNull?.split(" ")?.filter { it.isNotBlank() }
                ?: emptyList()
        }
    )
}

// A plain JVM entry point rather than `compose.desktop.application`: the studio
// never opens a window, and the packaging DSL would drag installer tooling into
// a module whose only output is PNG files.
tasks.register<JavaExec>("shoot") {
    group = "verification"
    description = "Renders the play stage offscreen to PNG files."
    dependsOn("jvmMainClasses")
    mainClass.set("com.kaiharimoto.mastertool.studio.StudioKt")
    // `app/`, so the sample deck at the repository root is one `..` away and
    // the default needs no absolute path in it.
    workingDir = rootProject.projectDir
    classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles +
        kotlin.jvm().compilations.getByName("main").output.allOutputs
    // Skia has no GPU here and never needs one: every frame lands on a raster
    // surface. Saying so up front stops Skiko probing for a GL context that a
    // headless container does not have.
    jvmArgs("-Djava.awt.headless=true", "-Dskiko.renderApi=SOFTWARE")
    // Args come through as `-Pshot.args="..."` so the whole thing is one Gradle
    // invocation from a script.
    argumentProviders.add(
        CommandLineArgumentProvider {
            providers.gradleProperty("shot.args").orNull?.split(" ")?.filter { it.isNotBlank() }
                ?: emptyList()
        }
    )
}

// Neue Master Tool, one picture per run. Same headless raster as `shoot`.
tasks.register<JavaExec>("shootNeue") {
    group = "verification"
    description = "Renders a page of Neue Master Tool offscreen to a PNG file."
    dependsOn("jvmMainClasses")
    mainClass.set("com.kaiharimoto.mastertool.studio.NeueStudio")
    workingDir = rootProject.projectDir
    classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles +
        kotlin.jvm().compilations.getByName("main").output.allOutputs
    jvmArgs("-Djava.awt.headless=true", "-Dskiko.renderApi=SOFTWARE", "-Dneue.version=studio")
    argumentProviders.add(
        CommandLineArgumentProvider {
            providers.gradleProperty("shot.args").orNull?.split(" ")?.filter { it.isNotBlank() }
                ?: emptyList()
        }
    )
}

// The card foil alone, drawn by the app's own shader at scripted pointer positions.
tasks.register<JavaExec>("shootFoil") {
    group = "verification"
    description = "Renders Neue's card foil offscreen to PNG files."
    dependsOn("jvmMainClasses")
    mainClass.set("com.kaiharimoto.mastertool.studio.FoilStudio")
    workingDir = rootProject.projectDir
    classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles +
        kotlin.jvm().compilations.getByName("main").output.allOutputs
    jvmArgs("-Djava.awt.headless=true", "-Dskiko.renderApi=SOFTWARE")
    argumentProviders.add(
        CommandLineArgumentProvider {
            providers.gradleProperty("shot.args").orNull?.split(" ")?.filter { it.isNotBlank() }
                ?: emptyList()
        }
    )
}

tasks.register<JavaExec>("shootNames") {
    group = "verification"
    description = "Renders the foil-name exploration: card names stamped in the foil, beside the cards as they are."
    dependsOn("jvmMainClasses")
    mainClass.set("com.kaiharimoto.mastertool.studio.NameFoilStudio")
    workingDir = rootProject.projectDir
    classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles +
        kotlin.jvm().compilations.getByName("main").output.allOutputs
    jvmArgs("-Djava.awt.headless=true", "-Dskiko.renderApi=SOFTWARE")
    argumentProviders.add(
        CommandLineArgumentProvider {
            providers.gradleProperty("shot.args").orNull?.split(" ")?.filter { it.isNotBlank() }
                ?: emptyList()
        }
    )
}
