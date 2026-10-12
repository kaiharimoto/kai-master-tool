package com.kaiharimoto.mastertool.core.present.record

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * A camera's pictures as a file of the app's own (I.9, recording on a phone or tablet, [Take.CAMERA_FRAMES]): each picture
 * a JPEG stamped with its moment on the take's clock, written while a take records and read back in order when it is
 * rendered. The desk writes Matroska through FFmpeg ([Take.CAMERA]); a phone has no FFmpeg — its Android natives alone are
 * 23 MB — and needs none for this, since a take never leaves the device it was made on ([TakePaths.syncs]). The desk's
 * renderer reads this file too, so a take is a take wherever it is drawn.
 *
 * The file: [MAGIC], then a record a picture — its moment in ms (8 bytes), its length (4 bytes), both big-endian, then its
 * JPEG's bytes. A record cut short (the app closed mid-write) ends the file: every picture before it reads.
 */
object CameraFrames {
    /** The file's first eight bytes: "NMTCAMF1". */
    val MAGIC: ByteArray = "NMTCAMF1".encodeToByteArray()

    /** Pictures a second kept, at most: a picture is placed in its slot of 1/[FPS] s, as the desk's camera file is. */
    const val FPS = 30

    /** The largest picture a record may say it holds: past this, the file is not one of these. */
    const val MOST_BYTES = 32 * 1024 * 1024

    /** Whether [file] is one of these, by its first bytes. */
    fun isFrames(file: File): Boolean = runCatching {
        if (file.length() < MAGIC.size) return false
        val head = ByteArray(MAGIC.size)
        RandomAccessFile(file, "r").use { it.readFully(head) }
        head.contentEquals(MAGIC)
    }.getOrDefault(false)

    /** The slot of 1/[fps] s a picture taken at [atMs] stands in. */
    fun slot(atMs: Long, fps: Int = FPS): Long = (atMs * fps + 500) / 1000

    /**
     * Pictures written into [file] as a take records. A picture whose slot is taken — the camera was quicker than [fps],
     * or a late picture's moment comes before the last — is not kept ([wants] says so first, so it is never encoded).
     */
    class Writer(file: File, private val fps: Int = FPS) : Closeable {
        private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16)).apply { write(MAGIC) }
        private var last = -1L
        private var closed = false

        /** Pictures written so far. */
        var count: Int = 0
            private set

        /** Whether a picture taken at [atMs] would be kept. */
        @Synchronized
        fun wants(atMs: Long): Boolean = !closed && slot(atMs, fps) > last

        /** [length] bytes of [jpeg], taken at [atMs], kept when its slot is free. True when it was. */
        @Synchronized
        fun write(jpeg: ByteArray, atMs: Long, length: Int = jpeg.size): Boolean {
            require(length in 0..minOf(jpeg.size, MOST_BYTES)) { "a picture of $length bytes" }
            if (closed) return false
            val s = slot(atMs, fps)
            if (s <= last) return false
            last = s
            out.writeLong(atMs)
            out.writeInt(length)
            out.write(jpeg, 0, length)
            count++
            return true
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            try {
                out.flush()
            } finally {
                out.close()
            }
        }
    }

    /** One picture in a file: its moment, and where its bytes are. */
    data class Entry(val atMs: Long, val offset: Long, val length: Int)

    /** A file's pictures, listed as it opens (its records walked, the pictures not read) and read one by one. */
    class Reader(file: File) : Closeable {
        private val raf = RandomAccessFile(file, "r")

        /** Every whole picture in the file, in the order written. */
        val entries: List<Entry> = scan()

        private fun scan(): List<Entry> {
            val size = raf.length()
            val head = ByteArray(MAGIC.size)
            if (size < MAGIC.size) return emptyList()
            raf.seek(0)
            raf.readFully(head)
            if (!head.contentEquals(MAGIC)) {
                raf.close()
                throw IllegalArgumentException("not a camera frames file")
            }
            val out = ArrayList<Entry>()
            var at = MAGIC.size.toLong()
            while (at + 12 <= size) {
                raf.seek(at)
                val ms = raf.readLong()
                val length = raf.readInt()
                if (length < 0 || length > MOST_BYTES || at + 12 + length > size) break
                out += Entry(ms, at + 12, length)
                at += 12 + length
            }
            return out
        }

        /** Picture [i]'s bytes. */
        fun read(i: Int): ByteArray {
            val e = entries[i]
            val bytes = ByteArray(e.length)
            synchronized(raf) {
                raf.seek(e.offset)
                raf.readFully(bytes)
            }
            return bytes
        }

        override fun close() = raf.close()
    }

    /**
     * A file read front to back as a video's frames ask for its pictures ([RenderPlan.advanceCamera]): for each frame's
     * moment, the newest picture at or before it — once, when it is a new one, and null while the picture stands.
     */
    class Track(private val reader: Reader) {
        private var next = 0

        fun advanceTo(atMs: Long): ByteArray? {
            var chosen = -1
            while (RenderPlan.advanceCamera(reader.entries.getOrNull(next)?.atMs, atMs)) chosen = next++
            return if (chosen >= 0) reader.read(chosen) else null
        }
    }
}
