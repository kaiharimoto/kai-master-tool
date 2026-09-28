package com.kaiharimoto.neue.platform

import java.io.File

/**
 * GETs [url] into [target], following redirects, with [headers]; [onProgress]
 * hears 0..1 while the length is known ([expected] stands in when the server
 * does not say). Returns the HTTP status; [target] holds the body whatever it
 * was, for the caller to judge. Blocking — call it off the main thread.
 *
 * Each platform's own client: the desktop's `java.net.http`, as Neue always
 * used, and Android's `HttpURLConnection`, which is there on every version.
 */
internal expect fun httpDownload(
    url: String,
    target: File,
    headers: Map<String, String> = emptyMap(),
    timeoutSeconds: Long = 30,
    expected: Long = -1,
    onProgress: ((Float) -> Unit)? = null,
): Int

/** Where a downloaded installer goes before it is handed on. */
internal expect val downloadDir: File

/**
 * Starts [installer], a new build of Neue, the way the platform installs one;
 * what to tell the person afterwards, if anything. May not return (Windows
 * quits so the installer can replace it).
 */
internal expect fun handOffInstaller(installer: File): String?
