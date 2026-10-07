package com.kaiharimoto.neue

import com.kaiharimoto.neue.duel.Duels
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No class the app ships calls the compiler's non-local-return marker (1.0.74). A `return@key`, a
 * `return@forEach` or a `return@Box` out of an inline lambda with composable calls in it can leave
 * `$$$$$NON_LOCAL_RETURN$$$$$."<anonymous>"` in the bytecode, and the class then fails to load — not
 * at compile time but the moment the screen first draws (the Duel page's first picture). Write an
 * `if` instead.
 */
class NonLocalReturnTest {
    @Test
    fun noClassCarriesTheMarker() {
        // Neue's classes and the table's (`:table`, the duel's own since the Lounge): a folder of classes in a build,
        // a jar when a module is taken as one. Both read; never a place quietly skipped.
        val roots = listOf(NeueHolders::class.java, Duels::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()) }.distinct()
        val marker = "NON_LOCAL_RETURN".toByteArray()
        fun carries(bytes: ByteArray) = (0..bytes.size - marker.size).any { i -> marker.indices.all { bytes[i + it] == marker[it] } }
        var read = 0
        val bad = roots.flatMap { root ->
            if (root.isDirectory) {
                root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.mapNotNull { f ->
                    read++
                    f.relativeTo(root).path.takeIf { carries(f.readBytes()) }
                }.toList()
            } else {
                ZipFile(root).use { zip ->
                    zip.entries().asSequence().filter { it.name.endsWith(".class") }.mapNotNull { e ->
                        read++
                        e.name.takeIf { carries(zip.getInputStream(e).readBytes()) }
                    }.toList()
                }
            }
        }
        assertTrue(read > 500, "Read only $read classes from $roots: the test is looking in the wrong place")
        assertTrue(bad.isEmpty(), "Classes carrying the non-local-return marker: $bad")
    }
}
