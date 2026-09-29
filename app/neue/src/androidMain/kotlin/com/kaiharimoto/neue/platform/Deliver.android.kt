package com.kaiharimoto.neue.platform

import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual suspend fun deliverFile(name: String, mime: String, bytes: ByteArray): String? {
    val context = Platform.context
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            // The provider's shared folder: the one path it hands out.
            val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, name).apply { writeBytes(bytes) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    } ?: return "$name could not be written"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        null
    }.getOrElse { "Nothing here can take a $mime file" }
}
