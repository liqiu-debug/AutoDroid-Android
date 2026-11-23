package org.autojs.autojs.runtime.api

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.autojs.autojs.core.image.ImageWrapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Created by SuperMonster003 on Mar 18, 2023.
 */
// @Reference to TonyJiangWJ/Auto.js (https://github.com/TonyJiangWJ/Auto.js) by SuperMonster003 on Mar 18, 2023.
class OcrMLKit {

    private var recognizer: TextRecognizer? = null

    @Synchronized private fun initIfNeeded() {
        recognizer ?: let {
            recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        }
    }

    fun release() {
        recognizer?.close()
    }

    fun detect(image: ImageWrapper?): List<OcrResult> {
        image?.takeUnless { image.isRecycled } ?: return emptyList()
        val bitmap = image.bitmap
        if (bitmap.isRecycled) return emptyList<OcrResult>().also { image.shoot() }
        return try { detectBitmap(bitmap) } finally { image.shoot() }
    }

    /** A caller-owned frame and a monotonic deadline let image matching use OCR
     * from exactly the same pixels. Cancellation never becomes an empty result. */
    @Throws(TimeoutException::class, InterruptedException::class)
    fun detectBitmap(bitmap: Bitmap, deadlineNanos: Long = Long.MAX_VALUE): List<OcrResult> {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("OCR interrupted")
        if (System.nanoTime() >= deadlineNanos) throw TimeoutException("ocr_deadline")
        initIfNeeded()
        require(!bitmap.isRecycled) { "OCR image was recycled" }
        // ML Kit may still be reading after the caller's deadline. Own that
        // frame until its completion callback so timeout cannot recycle it.
        val ownedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val inputImage = InputImage.fromBitmap(ownedBitmap, 0)
        val detectCompletedSignal = CountDownLatch(1)
        val result = try { recognizer!!.process(inputImage) } catch (error: Exception) { ownedBitmap.recycle();throw error }
        result.addOnCompleteListener({ command -> command.run() }) { ownedBitmap.recycle();detectCompletedSignal.countDown() }
        while (!result.isComplete) {
            try {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0) throw TimeoutException("ocr_deadline")
                detectCompletedSignal.await(minOf(50_000_000L, remaining), TimeUnit.NANOSECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }
        if (System.nanoTime() >= deadlineNanos) throw TimeoutException("ocr_deadline")
        if (!result.isSuccessful) {
            throw IllegalStateException("OCR detection failed", result.exception)
        }
        val ocrResults = ArrayList<OcrResult>()
        result.result.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                OcrResult(line.text, line.confidence, line.boundingBox!!).run {
                    ocrResults.add(this)
                }
            }
        }
        return ocrResults
    }

    fun recognizeText(image: ImageWrapper?): List<String> {
        image?.takeUnless { image.isRecycled } ?: return emptyList()
        initIfNeeded()
        val words = detect(image).sorted()
        return mutableListOf<String>().also { list ->
            words.indices.forEach { i -> words[i].label.let { list.add(it) } }
        }
    }

    companion object {

        private val TAG: String = Companion::class.java.simpleName

    }

}
