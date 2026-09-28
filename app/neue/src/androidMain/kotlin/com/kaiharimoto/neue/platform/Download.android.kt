package com.kaiharimoto.neue.platform

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal actual fun httpDownload(
    url: String,
    target: File,
    headers: Map<String, String>,
    timeoutSeconds: Long,
    expected: Long,
    onProgress: ((Float) -> Unit)?,
): Int {
    var connection = URL(url).openConnection() as HttpURLConnection
    // HttpURLConnection will not follow a redirect from one host to another on its
    // own when the scheme changes, and GitHub's assets are one hop away: follow by hand.
    var hops = 0
    while (true) {
        connection.connectTimeout = 20_000
        connection.readTimeout = (timeoutSeconds * 1000).toInt()
        connection.instanceFollowRedirects = true
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        val code = connection.responseCode
        if (code in 300..399 && hops++ < 5) {
            val next = connection.getHeaderField("Location") ?: return code
            connection.disconnect()
            connection = URL(URL(url), next).openConnection() as HttpURLConnection
            continue
        }
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: expected.takeIf { it > 0 }
        val input = if (code in 200..299) connection.inputStream else connection.errorStream
        input?.use { stream ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    read += n
                    if (total != null) onProgress?.invoke((read.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        connection.disconnect()
        return code
    }
}

internal actual val downloadDir: File get() = File(Platform.dataDir.parentFile, "cache/updates").apply { mkdirs() }

internal actual fun handOffInstaller(installer: File): String? {
    Platform.open(installer)
    return null
}
