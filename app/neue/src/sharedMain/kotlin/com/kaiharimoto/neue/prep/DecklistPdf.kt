package com.kaiharimoto.neue.prep

import com.kaiharimoto.mastertool.core.pdf.TrueType
import com.kaiharimoto.mastertool.core.prep.DecklistSheet
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import com.kaiharimoto.mastertool.core.ydk.JvmZlib
import com.kaiharimoto.neue.res.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The decklist sheet (`DecklistSheet`, core) set in the app's own fonts, as the siding guide is. */
object DecklistPdf {
    suspend fun write(content: DecklistSheet.Content): ByteArray {
        val fonts = GuideFonts(
            TrueType(Res.readBytes("font/inter_regular.ttf")),
            TrueType(Res.readBytes("font/inter_bold.ttf")),
            TrueType(Res.readBytes("font/jetbrainsmono_regular.ttf")),
        )
        return withContext(Dispatchers.Default) { DecklistSheet.write(content, fonts, JvmZlib) }
    }
}
