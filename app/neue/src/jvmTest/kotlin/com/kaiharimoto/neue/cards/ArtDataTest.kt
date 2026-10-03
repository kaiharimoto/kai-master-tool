package com.kaiharimoto.neue.cards

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** A person's own picture reaches the image loader as the file it is (1.0.89: it failed on Windows as text). */
class ArtDataTest {
    @Test
    fun aFileAddressIsTheFile() {
        val file = File.createTempFile("own-art", ".png")
        assertEquals(file.absoluteFile, artData(file.toURI().toString()))
        assertEquals("https://images.ygoprodeck.com/images/cards_small/14558127.jpg", artData("https://images.ygoprodeck.com/images/cards_small/14558127.jpg"))
        assertEquals(null, artData(null))
        file.delete()
    }
}

class DroppedLinkTest {
    @Test
    fun aBrowsersImageIsItsSource() {
        val html = """<html><body><a href="https://example.com/page"><img alt="x" src="https://images.example.com/a.png?w=1&amp;h=2"></a></body></html>"""
        assertEquals("https://images.example.com/a.png?w=1&h=2", com.kaiharimoto.neue.platform.imageSource(html))
        assertEquals(null, com.kaiharimoto.neue.platform.imageSource("<p>no picture</p>"))
    }
}
