package com.kaiharimoto.neue.mapper

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desk's trainer without a Python (I.10): its package is the repository's own, shipped whole inside the app and laid
 * out by its hash; a network that is not there is refused in words; a deck's `train/` folder is written whole.
 */
class MapperTrainerTest {
    private val sources = File("../../tools/mapper-train/mapper_train")

    @Test
    fun theTrainerShippedIsTheRepositorysOwnLaidOutByItsHash() {
        val home = Files.createTempDirectory("trainer").toFile()
        try {
            val dir = MapperTrainer.install(home)
            assertEquals(home, dir.parentFile)
            assertEquals(12, dir.name.length)
            val shipped = sources.listFiles { f -> f.name.endsWith(".py") }!!.sortedBy { it.name }
            assertTrue(shipped.size >= 8, "read the repository's trainer: ${shipped.map { it.name }}")
            shipped.forEach { f -> assertEquals(f.readText(), File(dir, "mapper_train/${f.name}").readText(), f.name) }
            // A file changed on disk is put back as the app's; the same package lands in the same folder.
            File(dir, "mapper_train/train.py").writeText("print('not the trainer')")
            assertEquals(dir, MapperTrainer.install(home))
            assertEquals(File(sources, "train.py").readText(), File(dir, "mapper_train/train.py").readText())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun aNetworkThatIsNotThereIsRefusedInWords() {
        val missing = MapperTrainer.load(File("no-such-network.onnx"), 3)
        assertTrue(missing.isFailure)
        assertTrue(missing.exceptionOrNull()!!.message!!.isNotBlank())
    }

    @Test
    fun aDecksTrainingFolderIsWrittenWhole() {
        val dir = Files.createTempDirectory("train").toFile()
        try {
            val store = FileTrainStore(dir)
            store.write("run/vocab.json", "{}")
            store.write("rounds/2/checkpoint.pt", "w")
            assertEquals("{}", store.read("run/vocab.json"))
            assertEquals(listOf("2"), store.list("rounds"))
            assertTrue(File(dir, "run").list()!!.none { it.startsWith(".") }, "no file half written beside it")
            store.delete("rounds")
            assertEquals(emptyList(), store.list("rounds"))
            assertEquals(File(dir, "nets/net-1.onnx").absolutePath, store.path("nets/net-1.onnx"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
