package com.kaiharimoto.neue.ai

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// The two folders of the data folder that are this device's alone (1.0.99, the red team): the keys, and the
// command-line apps' working folder. Neither is under Ai's folder, so neither is synced, backed up, or anywhere a
// CLI is pointed at; `InboundPath.DEVICE_FOLDERS` refuses both to anything arriving from outside. `docs/SECURITY.md`.

/**
 * Readable and writable by its owner alone, where the file system can say so: POSIX `rw-------` (`rwx------` for a
 * folder) on Linux and macOS; on Windows an access list naming the owner alone, best effort. Never throws.
 */
object OwnerOnly {
    /** Whether [f] is now its owner's alone, as far as the file system could be told. */
    fun apply(f: File): Boolean {
        val path = f.toPath()
        val dir = f.isDirectory
        if (runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (dir) "rwx------" else "rw-------")) }.isSuccess) return true
        val acl = runCatching {
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: error("No access lists here")
            val inherit = if (dir) setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) else emptySet()
            view.acl = listOf(
                AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.owner)
                    .setPermissions(AclEntryPermission.values().toSet()).setFlags(inherit).build(),
            )
        }.isSuccess
        if (acl) return true
        return runCatching {
            f.setReadable(false, false) && f.setReadable(true, true) && f.setWritable(false, false) && f.setWritable(true, true)
        }.getOrDefault(false)
    }

    /** [text] written to [f], made its owner's alone before the first byte goes in. */
    fun writeText(f: File, text: String) {
        f.parentFile?.mkdirs()
        f.delete()
        val made = runCatching {
            Files.createFile(f.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }.isSuccess
        if (!made) {
            f.createNewFile()
            apply(f)
        }
        f.writeText(text)
    }
}

/**
 * Where Ai's keys are kept (1.0.99): `<data>/secrets/`, a folder of their own. Until 1.0.98 the file sat in
 * `<data>/ai/`, beside the memory and the folder the command-line apps ran in. Its format is unchanged; only its
 * place moved, on first read ([migrate]).
 */
object SecretFiles {
    const val FOLDER = "secrets"

    /** Where the keys were kept until 1.0.98. */
    const val OLD_FOLDER = "ai"

    /** The half-written file a store leaves beside its keys while it saves. */
    const val TEMP = ".credentials.tmp"

    /** The keys file [name] under [data], moved there from its pre-1.0.99 place the first time it is asked for. */
    fun file(data: File, name: String): File {
        val folder = File(data, FOLDER)
        folder.mkdirs()
        OwnerOnly.apply(folder)
        val now = File(folder, name)
        val old = File(File(data, OLD_FOLDER), name)
        migrate(old, now)
        // A move that could not be made leaves the keys where they were, still read, rather than lost.
        return if (!now.isFile && old.isFile) old else now
    }

    /**
     * [old] moved to [now] when [now] is not there yet, byte for byte, and then [old] deleted. An [old] left beside a
     * [now] that already exists is deleted too, so no key stays in Ai's folder, and so is a half-written temp beside it.
     */
    fun migrate(old: File, now: File) {
        if (old.isFile && !now.exists()) {
            now.parentFile?.mkdirs()
            val moved = runCatching { Files.move(old.toPath(), now.toPath(), StandardCopyOption.ATOMIC_MOVE) }.isSuccess
            if (!moved) runCatching {
                // Another file system: a copy, checked, then the original goes.
                val temp = File(now.parentFile, TEMP)
                temp.delete()
                Files.copy(old.toPath(), temp.toPath())
                OwnerOnly.apply(temp)
                if (!temp.readBytes().contentEquals(old.readBytes()) || !temp.renameTo(now)) temp.delete()
            }
        }
        if (now.isFile) {
            OwnerOnly.apply(now)
            if (old.isFile) old.delete()
        }
        File(old.parentFile, TEMP).delete()
    }
}

/**
 * The command-line apps' working folder (1.0.99): `<data>/cli-run/`, outside Ai's folder, so the keys, memory,
 * conversations and pictures are nowhere a CLI is pointed at. It is empty but for the two files a Claude Code turn
 * needs while it runs, its instructions and the app's MCP server with this launch's token, each written owner-only
 * under a name of its own ([turnFile]) and deleted when the turn ends, or when the app quits. It stays one folder,
 * not one a launch, because Claude Code files its conversations by working folder and a conversation goes on by
 * `--resume` there.
 */
object CliRun {
    const val FOLDER = "cli-run"

    /** Where the CLIs ran until 1.0.98: inside Ai's folder, the last launch's token in a plain file. */
    const val OLD_FOLDER = "ai/run"

    private val swept = AtomicBoolean(false)

    /** The working folder under [data]; the first time in a launch, [prepare]d. */
    fun folder(data: File): File =
        if (swept.compareAndSet(false, true)) prepare(data) else File(data, FOLDER).apply { mkdirs() }

    /**
     * The working folder made its owner's alone and emptied of what a crash left (a dead token, old instructions;
     * nothing else belongs there), and the pre-1.0.99 folder in Ai's folder deleted. Run before any turn starts.
     */
    fun prepare(data: File): File {
        File(data, OLD_FOLDER).deleteRecursively()
        val dir = File(data, FOLDER)
        dir.mkdirs()
        OwnerOnly.apply(dir)
        dir.listFiles()?.forEach { it.deleteRecursively() }
        return dir
    }

    /** A file of this turn's own in [dir], [text] in it, owner-only from the first byte. The caller deletes it when the turn ends. */
    fun turnFile(dir: File, stem: String, extension: String, text: String): File {
        val f = File(dir, "$stem-${UUID.randomUUID()}.$extension")
        OwnerOnly.writeText(f, text)
        // If the app quits mid-turn, the file goes with it.
        f.deleteOnExit()
        return f
    }
}
