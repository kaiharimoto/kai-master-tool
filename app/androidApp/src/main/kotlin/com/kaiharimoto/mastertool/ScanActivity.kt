package com.kaiharimoto.mastertool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Decoder
import com.journeyapps.barcodescanner.DecoderFactory
import com.kaiharimoto.mastertool.core.ydk.DeckQrParts

/**
 * The camera, reading a deck's QR code (v1.3.7), and every part of a split one
 * (1.0.32, kai: a deck past one code is several, and "don't have it show one at a
 * time… the user would need to click every time"). The desk shows all the parts
 * at once; this stays open and reads *every* code in each frame — ZXing's multi
 * reader behind the embedded scanner's view — counting "2 of 3" on the screen
 * with a tick for each new part, and returns the joined code once the last is in.
 * Nothing to press: the camera is swept across the grid.
 *
 * A lone code, Neue's or anyone's `ydke://`, returns at once. The collected parts
 * outlive a turn of the phone, which recreates the activity.
 */
class ScanActivity : ComponentActivity() {

    private lateinit var view: DecoratedBarcodeView
    private val parts = DeckQrParts()
    private val main = Handler(Looper.getMainLooper())
    private var done = false

    /** The parts read before a turn recreated the activity. */
    private val seen = mutableListOf<String>()

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) view.resume() else finishWith(null, noCamera = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = DecoratedBarcodeView(this)
        view.decoderFactory = MultiDecoderFactory { texts -> main.post { onRead(texts) } }
        view.setStatusText(PROMPT)
        // Edge to edge (targeting SDK 35+): the prompt stays clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(view)
        // The decoder runs on the scanner's own thread and posts what it finds here.
        view.decodeContinuous { }
        savedInstanceState?.getStringArrayList(SEEN)?.let { restored -> onRead(restored) }
        asked = savedInstanceState?.getBoolean(ASKED) ?: false
    }

    /** Whether the camera has been asked for: once, or a refusal would be asked again as the dialog closes. */
    private var asked = false

    override fun onResume() {
        super.onResume()
        if (done) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            view.resume()
        } else if (!asked) {
            asked = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        super.onPause()
        view.pause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(SEEN, ArrayList(seen))
        outState.putBoolean(ASKED, asked)
    }

    private fun onRead(texts: List<String>) {
        if (done) return
        for (text in texts) {
            when (val offer = parts.offer(text)) {
                is DeckQrParts.Offer.Whole -> return finishWith(offer.text)
                is DeckQrParts.Offer.Part -> if (offer.new) {
                    if (offer.have == 1) seen.clear()
                    seen += text
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    view.setStatusText("${offer.have} of ${offer.of} codes read. Keep the camera moving across them")
                }
            }
        }
    }

    private fun finishWith(text: String?, noCamera: Boolean = false) {
        if (done) return
        done = true
        setResult(
            if (text != null) RESULT_OK else RESULT_CANCELED,
            Intent().putExtra(TEXT, text).putExtra(NO_CAMERA, noCamera),
        )
        finish()
    }

    /**
     * Every code in a frame (ZXing's multi reader), handed to [found]; then, for a
     * lone code at an angle the multi reader's finder misses, the ordinary reader.
     * It returns nothing to the view: the collecting is this activity's.
     */
    private class MultiDecoderFactory(private val found: (List<String>) -> Unit) : DecoderFactory {
        override fun createDecoder(baseHints: Map<DecodeHintType, *>): Decoder {
            val hints = baseHints + (DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
            return object : Decoder(QRCodeReader()) {
                override fun decode(bitmap: BinaryBitmap): Result? {
                    val texts = try {
                        QRCodeMultiReader().decodeMultiple(bitmap, hints).map { it.text }
                    } catch (_: ReaderException) {
                        emptyList()
                    }.ifEmpty {
                        try {
                            listOf(QRCodeReader().decode(bitmap, hints).text)
                        } catch (_: ReaderException) {
                            emptyList()
                        }
                    }
                    if (texts.isNotEmpty()) found(texts)
                    return null
                }
            }
        }
    }

    companion object {
        const val TEXT = "text"
        const val NO_CAMERA = "noCamera"
        private const val SEEN = "seen"
        private const val ASKED = "asked"
        private const val PROMPT = "Hold the camera on the deck's QR code. A deck in several codes: move across them, in any order"
    }
}
