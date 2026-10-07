plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/*
 * The Lounge's browser app (`docs/LOUNGE.md`): what a friend opens at kai's address — the passcode, a nickname, the
 * lobby, their decks, and the duel table itself, Neue's own (`:table`), compiled to WebAssembly. It is served by
 * kai's computer and shipped inside the desktop installer, so the page is always the version of the app serving it.
 *
 * It depends on :table, :builder and :core alone: nothing of Ai, its keys, the art library or kai's files can be in it.
 */
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("guest")
        browser {
            commonWebpackConfig { outputFileName = "guest.js" }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":table"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.ktor.client.js)
        }
    }
}

/*
 * The page as kai's computer serves it: the compiled module, Skia's WebAssembly runtime beside it, the resources and
 * index.html, in one folder — gathered by hand from the compiler's output rather than through webpack, so a build needs
 * nothing fetched from GitHub. `-Pmastertool.guestOptimize=true` takes binaryen's optimised module instead (CI).
 */
val optimize = providers.gradleProperty("mastertool.guestOptimize").orNull?.toBooleanStrictOrNull() ?: false
val compile = if (optimize) "compileProductionExecutableKotlinWasmJsOptimize" else "compileProductionExecutableKotlinWasmJs"
val guestBundle = tasks.register<Sync>("guestBundle") {
    group = "build"
    description = "The Lounge's page, ready for kai's computer to serve (build/lounge)."
    dependsOn(compile, "wasmJsProcessResources")
    from(layout.buildDirectory.dir(if (optimize) "compileSync/wasmJs/main/productionExecutable/optimized" else "compileSync/wasmJs/main/productionExecutable/kotlin"))
    from(layout.buildDirectory.dir("processedResources/wasmJs/main"))
    // Skia's runtime, from the dependency that carries it.
    from(configurations.named("wasmJsRuntimeClasspath").map { cp ->
        cp.filter { it.name.startsWith("skiko-js-wasm-runtime") }.map { zipTree(it) }
    }) {
        include("skiko.mjs", "skiko.wasm", "skikod8.mjs", "js-reexport-symbols.mjs")
    }
    exclude("**/*.map")
    into(layout.buildDirectory.dir("lounge"))
}
