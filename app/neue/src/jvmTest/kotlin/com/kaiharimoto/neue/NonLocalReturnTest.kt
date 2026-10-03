package com.kaiharimoto.neue

import com.kaiharimoto.neue.duel.Duels
import java.io.File
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
        val root = File(Duels::class.java.protectionDomain.codeSource.location.toURI())
        if (!root.isDirectory) return
        val marker = "NON_LOCAL_RETURN".toByteArray()
        val bad = root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.filter { f ->
            val bytes = f.readBytes()
            (0..bytes.size - marker.size).any { i -> marker.indices.all { bytes[i + it] == marker[it] } }
        }.map { it.relativeTo(root).path }.toList()
        assertTrue(bad.isEmpty(), "Classes carrying the non-local-return marker: $bad")
    }
}
