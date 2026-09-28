package com.kaiharimoto.neue.platform

import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.mastertool.core.update.MacInstall
import java.io.File
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

private val client: HttpClient by lazy {
    HttpClient.newBuilder()
        .proxy(ProxySelector.getDefault())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()
}

internal actual fun httpDownload(
    url: String,
    target: File,
    headers: Map<String, String>,
    timeoutSeconds: Long,
    expected: Long,
    onProgress: ((Float) -> Unit)?,
): Int {
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .GET()
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    val total = response.headers().firstValueAsLong("Content-Length").orElse(expected).takeIf { it > 0 }
    response.body().use { input ->
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var read = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                read += n
                if (total != null) onProgress?.invoke((read.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }
    return response.statusCode()
}

internal actual val downloadDir: File get() = File(System.getProperty("java.io.tmpdir"))

internal actual fun handOffInstaller(installer: File): String? = when (Platform.os) {
    DesktopOs.WINDOWS -> {
        // The launcher that started this process is the one the new build replaces.
        val exe = ProcessHandle.current().info().command().orElse(null)
        val script = File(installer.parentFile, "neue-update.cmd")
        script.writeText(
            buildString {
                appendLine("@echo off")
                appendLine("start \"\" /wait msiexec /i \"${installer.absolutePath}\" /passive /norestart")
                if (exe != null && exe.endsWith(".exe", ignoreCase = true) && !exe.contains("java", ignoreCase = true)) {
                    appendLine("start \"\" \"$exe\"")
                }
            },
        )
        ProcessBuilder("cmd", "/c", script.absolutePath).start()
        exitProcess(0)
    }
    DesktopOs.MAC -> {
        // In place (Phase 4): a script swaps the bundle once this process is
        // gone, then reopens it — `MacInstall`. A bundle that cannot be found
        // (a development run) or written (an Applications folder this user
        // does not own) falls back to opening the image for a drag.
        val bundle = ProcessHandle.current().info().command().orElse(null)?.let(MacInstall::bundleOf)
        val writable = bundle != null && File(bundle).parentFile?.canWrite() == true && File(bundle).canWrite()
        if (bundle != null && writable) {
            val script = File(installer.parentFile, "neue-update.sh")
            script.writeText(
                MacInstall.script(
                    dmg = installer.absolutePath,
                    bundle = bundle,
                    pid = ProcessHandle.current().pid(),
                    log = File(installer.parentFile, "neue-update.log").absolutePath,
                ),
            )
            script.setExecutable(true)
            ProcessBuilder("/usr/bin/nohup", "/bin/sh", script.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            exitProcess(0)
        }
        ProcessBuilder("open", installer.absolutePath).start()
        "Drag Neue Master Tool into Applications to replace this one, then reopen it"
    }
    DesktopOs.LINUX, DesktopOs.ANDROID -> {
        Platform.open(installer)
        "Install the package, then reopen Neue Master Tool"
    }
}
