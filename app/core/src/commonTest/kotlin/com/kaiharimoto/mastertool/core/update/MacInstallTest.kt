package com.kaiharimoto.mastertool.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacInstallTest {

    @Test
    fun theBundleIsReadOffTheLauncher() {
        assertEquals(
            "/Applications/Neue Master Tool.app",
            MacInstall.bundleOf("/Applications/Neue Master Tool.app/Contents/MacOS/Neue Master Tool"),
        )
        assertEquals(
            "/Users/kai/Applications/Neue Master Tool.app",
            MacInstall.bundleOf("/Users/kai/Applications/Neue Master Tool.app/Contents/runtime/Contents/Home/bin/java"),
        )
        // A development run is a bare JDK: there is no bundle to replace.
        assertNull(MacInstall.bundleOf("/Library/Java/JavaVirtualMachines/jdk-21/Contents/Home/bin/java"))
        assertNull(MacInstall.bundleOf("/usr/bin/java"))
    }

    @Test
    fun aPathIsOneShellWordWhateverItHoldsInIt() {
        assertEquals("'/Applications/Neue Master Tool.app'", MacInstall.quote("/Applications/Neue Master Tool.app"))
        assertEquals("'/Users/kai'\\''s Mac/x.dmg'", MacInstall.quote("/Users/kai's Mac/x.dmg"))
    }

    @Test
    fun theScriptWaitsSwapsAndReopens() {
        val script = MacInstall.script("/tmp/n.dmg", "/Applications/Neue Master Tool.app", 4242, "/tmp/log")
        val lines = script.lines()
        assertEquals("#!/bin/sh", lines.first())
        assertTrue("APP='/Applications/Neue Master Tool.app'" in lines)
        assertTrue("PID=4242" in lines)
        val order = listOf("kill -0", "hdiutil attach", "ditto", "mv \"\$APP\" \"\$APP.old\"", "hdiutil detach", "open \"\$APP\"")
        val at = order.map { needle -> script.indexOf(needle).also { assertTrue(it >= 0, needle) } }
        assertEquals(at.sorted(), at, "the steps are in order")
        // A swap that fails half-way puts the old app back.
        assertTrue("else mv \"\$APP.old\" \"\$APP\"" in script)
    }
}
