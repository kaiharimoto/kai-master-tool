package com.kaiharimoto.mastertool.core.ai.vision

import com.kaiharimoto.mastertool.core.TestCards
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.cli.ClaudeCli
import com.kaiharimoto.mastertool.core.ai.cli.CodexCli
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiWire
import com.kaiharimoto.mastertool.core.search.CardIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pictures sent to a model (1.0.55): their size, the wires' shapes, whether a model sees, names read off them. */
class VisionTest {
    private val picture = Part.Image("images/s/abc.png", "image/png", 800, 600, data = "QUJD")

    @Test
    fun aPictureIsSentAtTheSizeAModelReads() {
        assertEquals(800 to 600, PictureFit.size(800, 600), "small enough already")
        val (w, h) = PictureFit.size(4000, 3000)
        assertTrue(w <= PictureFit.LONG_SIDE && h <= PictureFit.LONG_SIDE)
        assertTrue(w.toLong() * h <= PictureFit.PIXELS)
        assertEquals(4f / 3f, w.toFloat() / h, 0.01f)
        val (tw, th) = PictureFit.size(1080, 2400)
        assertTrue(th <= PictureFit.LONG_SIDE && tw * th <= PictureFit.PIXELS, "a phone screenshot: $tw × $th")
        assertTrue(PictureFit.keepsPng(true, 400_000))
        assertFalse(PictureFit.keepsPng(true, 3_000_000))
        assertFalse(PictureFit.keepsPng(false, 10))
        assertEquals("image/png", PictureFit.mime("x.jpg", byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertEquals("image/jpeg", PictureFit.mime("x.png", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
    }

    @Test
    fun wordsAloneGoAsTheyAlwaysDidAndPicturesGoAsParts() {
        val words = OpenAiWire.messages("", listOf(ChatTurn.user("Hello")))
        assertEquals("Hello", words[0].jsonObject["content"]!!.jsonPrimitive.content, "a turn of words is still one string")
        val shown = OpenAiWire.messages("", listOf(ChatTurn.user("What deck is this?", images = listOf(picture))))
        val content = shown[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,QUJD", content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        // A picture whose bytes were lost is said, not dropped silently.
        val lost = OpenAiWire.messages("", listOf(ChatTurn.user("And this?", images = listOf(picture.copy(data = null)))))
        assertTrue(lost[0].jsonObject["content"]!!.jsonPrimitive.content.contains("could not be read"))
    }

    @Test
    fun aPictureIsNeverSavedInTheConversation() {
        val turn = ChatTurn.user("Look", images = listOf(picture))
        val saved = Json.encodeToString(ChatTurn.serializer(), turn)
        assertFalse("QUJD" in saved, "the bytes stay in their file")
        val back = Json.decodeFromString(ChatTurn.serializer(), saved)
        assertEquals(picture.copy(data = null), back.images.single())
        assertEquals(listOf("image"), ChatTurn.user("", images = listOf(picture)).parts.map { if (it is Part.Image) "image" else "other" })
    }

    @Test
    fun theClisTakePicturesTheirOwnWays() {
        val words = ClaudeCli.launch("claude", "hi", "s", "m", "", "", null)
        assertFalse("--input-format" in words.args)
        assertEquals("hi", words.stdin)
        val shown = ClaudeCli.launch("claude", "What is this?", "s", "m", "", "", null, listOf(picture))
        assertTrue(shown.args.windowed(2).any { it == listOf("--input-format", "stream-json") })
        val line = Json.parseToJsonElement(shown.stdin.trim()).jsonObject
        assertEquals("user", line["type"]!!.jsonPrimitive.content)
        val blocks = line["message"]!!.jsonObject["content"]!!.jsonArray
        assertEquals(listOf("text", "image"), blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("QUJD", blocks[1].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        val codex = CodexCli.launch("codex", "hi", "/w", "u", "t", "", "", null, listOf("/a/1.png", "/a/2.png"))
        assertTrue("--image=/a/1.png,/a/2.png" in codex.args)
        assertEquals("-", codex.args.last(), "the prompt still comes from stdin")
    }

    @Test
    fun whetherAModelSeesIsReadOffItsName() {
        assertEquals(Vision.Sight.YES, Vision.of("anthropic", "claude-opus-5-5"))
        assertEquals(Vision.Sight.YES, Vision.of("gemini", "gemini-2.5-pro"))
        assertEquals(Vision.Sight.YES, Vision.of("openai", "gpt-5"))
        assertEquals(Vision.Sight.YES, Vision.of("ollama", "qwen2.5-vl:7b"))
        assertEquals(Vision.Sight.NO, Vision.of("compatible", "deepseek-chat"))
        assertEquals(Vision.Sight.MAYBE, Vision.of("ollama", "some-new-model"))
        assertTrue(Vision.refused("This model does not support image input"))
        assertFalse(Vision.refused("Rate limit reached"))
    }

    @Test
    fun namesReadOffAPictureFindTheirCards() {
        assertEquals(1.0, NameMatch.score("Ash Blossom & Joyous Spring", "ASH BLOSSOM & JOYOUS SPRING"))
        assertTrue(NameMatch.score("Ash Blossom & Joyous Spnng", "Ash Blossom & Joyous Spring") > 0.9)
        assertTrue(NameMatch.score("Infinite Imperm", "Infinite Impermanence") > 0.6)
        assertTrue(NameMatch.score("Maxx C", "Nibiru, the Primal Being") < 0.35)
        val index = CardIndex.build(TestCards.all)
        val read = ReadCards.resolve(
            listOf(
                ReadCards.Read("Ash Blossom & Joyous Spnng", 3),
                ReadCards.Read("Nibiru the Primal Being"),
                ReadCards.Read("Infinite Impermanance", 2, "main"),
                ReadCards.Read("Blue-Eyes Nonsense Dragon"),
            ),
            index,
        )
        assertEquals("Ash Blossom & Joyous Spring", read[0].card?.name)
        assertEquals("Nibiru, the Primal Being", read[1].card?.name)
        assertTrue(read[1].sure)
        assertEquals("Infinite Impermanence", read[2].card?.name)
        assertFalse(read[3].sure)
        val said = ReadCards.describe(read)
        assertTrue(said.startsWith("${read.count { it.sure }} of 4"))
        assertTrue("[main]" in said)
    }

    @Test
    fun aPictureAloneStillTitlesTheConversation() {
        val session = com.kaiharimoto.mastertool.core.ai.AiSession("s", turns = listOf(ChatTurn.user("", images = listOf(picture)))).titled()
        assertEquals("A picture", session.title)
        assertNull(ChatTurn.user("x").images.firstOrNull())
    }
}
