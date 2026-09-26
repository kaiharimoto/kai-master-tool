package com.kaiharimoto.neue

import com.kaiharimoto.neue.zen.Textures
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The garden's SkSL is compiled when zen first draws, and a shader that will not
 * compile is logged and drawn as bare paper — so without this a typo is a zen
 * with no garden, found on kai's machine rather than here.
 */
class GardenShaderTest {
    @Test
    fun theGardenShaderCompiles() {
        assertNull(Textures.compileError())
    }
}
