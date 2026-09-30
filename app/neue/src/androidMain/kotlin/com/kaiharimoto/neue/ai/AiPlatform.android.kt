package com.kaiharimoto.neue.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.kaiharimoto.mastertool.core.ai.mcp.McpReply
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keys on a phone or a tablet: one JSON document, encrypted with AES-GCM under a key
 * that lives in the Android Keystore and never leaves it. The file alone, copied off
 * the device, is noise.
 */
actual object SecretStore {
    private const val ALIAS = "neue-ai-secrets"
    private val file get() = File(Platform.dataDir, "ai/credentials.bin")
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    @Synchronized
    private fun all(): Map<String, String> = runCatching {
        val bytes = file.readBytes()
        val iv = bytes.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val text = cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
        json.parseToJsonElement(text).jsonObject.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun write(values: Map<String, String>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString().toByteArray())
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".credentials.tmp")
        temp.writeBytes(sealed)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    actual fun get(key: String): String? = all()[key]

    actual fun put(key: String, value: String) = write(all() + (key to value))

    actual fun remove(key: String) = write(all() - key)
}

/** No CLIs on a phone or a tablet: every Ai connection there is an API. */
actual object AiDesk {
    actual val canRunCli: Boolean = false

    actual fun env(name: String): String? = null

    actual suspend fun which(program: String, hint: String?): String? = null

    actual suspend fun run(args: List<String>, stdin: String?, workDir: File?, env: Map<String, String>, timeoutMs: Long): ProcessResult =
        ProcessResult(-1, "", "Command-line apps run on the desktop app only.")

    actual fun lines(args: List<String>, stdin: String?, workDir: File?, env: Map<String, String>): Flow<ProcessLine> =
        flowOf(ProcessLine.Exit(-1, "Command-line apps run on the desktop app only."))

    actual fun openTerminal(command: String): Boolean = false

    actual fun startMcp(handle: suspend (authorization: String?, body: String) -> McpReply): McpHandle? = null
}
