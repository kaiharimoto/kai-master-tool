package com.kaiharimoto.mastertool.core.present

import com.kaiharimoto.mastertool.core.present.record.CameraFrames
import com.kaiharimoto.mastertool.core.present.record.Wav
import com.kaiharimoto.mastertool.core.present.record.WavReader
import com.kaiharimoto.mastertool.core.present.record.WavWriter
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A phone or tablet's camera file (I.9): pictures stamped on the take's clock, a slot of 1/30 s each, read back in order
 * as a render asks — and a file the app closed on mid-write still reads up to its last whole picture. With the WAV
 * written and read the same way on the desk and the phone.
 */
class CameraFramesTest {
    private val dir: File = Files.createTempDirectory("camera-frames").toFile()

    private fun picture(n: Int, size: Int = 100 + n) = ByteArray(size) { (n * 7 + it).toByte() }

    @Test
    fun picturesAreKeptInTheirSlotsAndReadBackInOrder() {
        val f = File(dir, "camera.frames")
        CameraFrames.Writer(f).use { w ->
            assertTrue(w.wants(0))
            assertTrue(w.write(picture(0), 0))
            // A second picture in the same 1/30 s is not kept, and was not wanted.
            assertFalse(w.wants(10))
            assertFalse(w.write(picture(1), 10))
            // A late picture whose moment is behind the last is not kept either.
            assertTrue(w.write(picture(2), 100))
            assertFalse(w.write(picture(3), 80))
            assertTrue(w.write(picture(4), 133))
            assertEquals(3, w.count)
            // The buffer's tail of a picture larger than asked is not written.
            assertTrue(w.write(picture(5, 300), 200, length = 50))
        }
        assertTrue(CameraFrames.isFrames(f))
        CameraFrames.Reader(f).use { r ->
            assertEquals(listOf(0L, 100L, 133L, 200L), r.entries.map { it.atMs })
            assertContentEquals(picture(0), r.read(0))
            assertContentEquals(picture(2), r.read(1))
            assertContentEquals(picture(4), r.read(2))
            assertContentEquals(picture(5, 300).copyOf(50), r.read(3))
        }
    }

    @Test
    fun aTrackHandsEachVideoFrameTheNewestPictureOnce() {
        val f = File(dir, "track.frames")
        CameraFrames.Writer(f).use { w -> for (n in 0 until 10) w.write(picture(n), n * 100L) }
        CameraFrames.Reader(f).use { r ->
            val t = CameraFrames.Track(r)
            // Before the first picture's moment there is none… then the picture at 0, which stands until 100.
            assertContentEquals(picture(0), t.advanceTo(0))
            assertNull(t.advanceTo(33))
            assertNull(t.advanceTo(66))
            assertContentEquals(picture(1), t.advanceTo(100))
            // A jump takes the newest at or before the moment, skipping those between.
            assertContentEquals(picture(5), t.advanceTo(550))
            assertNull(t.advanceTo(560))
            assertContentEquals(picture(9), t.advanceTo(10_000))
            assertNull(t.advanceTo(20_000))
        }
    }

    @Test
    fun aFileCutShortReadsUpToItsLastWholePicture() {
        val f = File(dir, "cut.frames")
        CameraFrames.Writer(f).use { w -> for (n in 0 until 5) w.write(picture(n), n * 40L) }
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 10) }
        CameraFrames.Reader(f).use { r ->
            assertEquals(4, r.entries.size)
            assertContentEquals(picture(3), r.read(3))
        }
        // An empty file — the app closed before the header was flushed — is no pictures, not a failure.
        val empty = File(dir, "empty.frames").apply { writeBytes(ByteArray(0)) }
        CameraFrames.Reader(empty).use { assertEquals(0, it.entries.size) }
    }

    @Test
    fun aFileAsI9WritesItReadsForGood() {
        // The bytes I.9 writes, spelled out: "NMTCAMF1", then a picture at 33 ms of 3 bytes and one at 1,000 ms of 2. A later
        // build that changes the format still reads this (takes are kept on the device for as long as the person wants).
        val bytes = "NMTCAMF1".encodeToByteArray() +
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 33) + byteArrayOf(0, 0, 0, 3) + byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1) +
            byteArrayOf(0, 0, 0, 0, 0, 0, 0x03, 0xE8.toByte()) + byteArrayOf(0, 0, 0, 2) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val f = File(dir, "i9.frames").apply { writeBytes(bytes) }
        assertTrue(CameraFrames.isFrames(f))
        CameraFrames.Reader(f).use { r ->
            assertEquals(listOf(33L, 1_000L), r.entries.map { it.atMs })
            assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1), r.read(0))
            assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), r.read(1))
        }
        // And the writer writes exactly that.
        val w = File(dir, "again.frames")
        CameraFrames.Writer(w).use {
            it.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1), 33)
            it.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), 1_000)
        }
        assertContentEquals(bytes, w.readBytes())
    }

    @Test
    fun aFileThatIsNotOneIsSaidSo() {
        val mkv = File(dir, "camera.mkv").apply { writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 2, 3, 4, 5, 6)) }
        assertFalse(CameraFrames.isFrames(mkv))
        assertFailsWith<IllegalArgumentException> { CameraFrames.Reader(mkv) }
        assertFalse(CameraFrames.isFrames(File(dir, "missing.frames")))
    }

    @Test
    fun aWavIsWrittenAndReadInEachFramesSamples() {
        val f = File(dir, "audio.wav")
        WavWriter(f, 48_000, 1).use { w ->
            val samples = ByteArray(4_800 * 2) { i -> if (i % 2 == 0) (i / 2 % 256).toByte() else 0 }
            w.write(samples, samples.size)
            w.silence(4_800)
        }
        val info = Wav.read(f.readBytes().copyOf(4096), f.length())!!
        assertEquals(48_000, info.rate)
        assertEquals(9_600L, info.frames)
        val r = WavReader.open(f)!!
        r.use {
            assertEquals(48_000, it.rate)
            assertEquals(1, it.channels)
            // A frame at 30 a second takes 1,600 samples; the running total keeps them in step.
            val first = it.take(1_600)
            assertEquals(1_600, first.size)
            assertEquals(5, first[5].toInt())
            assertEquals(1_600, it.take(3_200).size)
            // Past the file's end, silence.
            val tail = it.take(12_000)
            assertEquals(8_800, tail.size)
            assertTrue(tail.takeLast(2_400).all { s -> s.toInt() == 0 })
        }
        assertNull(WavReader.open(File(dir, "camera.frames").apply { writeBytes(CameraFrames.MAGIC) }))
    }
}
