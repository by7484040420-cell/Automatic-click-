package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/**
 * Screen ka screenshot leke usme text padhta hai (OCR).
 *
 * Isse Accessibility tree par depend nahi rehna padta: photo, screenshot, ya doosre phone ki
 * screen jo camera me dikh rahi ho - jo bhi PIXELS me dikhe, uska text mil jata hai aur
 * uski jagah (x, y) bhi.
 *
 * Android 11+ chahiye (AccessibilityService.takeScreenshot).
 */
object ScreenOcrScanner {

    class Line(val text: String, val box: Rect)

    class Result(val bitmap: Bitmap, val lines: List<Line>)

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val executor = Executors.newSingleThreadExecutor()

    /** Screenshot + OCR. Callback (result, errorMessage). Exactly ek hi non-null hoga. */
    fun capture(service: AccessibilityService, onResult: (Result?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null, "Android 11+ chahiye")
            return
        }
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val bmp: Bitmap? = try {
                            Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (e: Exception) {
                            null
                        } finally {
                            try { buffer.close() } catch (_: Exception) {}
                        }
                        if (bmp == null) {
                            onResult(null, "screenshot bitmap nahi bana")
                            return
                        }
                        recognizer.process(InputImage.fromBitmap(bmp, 0))
                            .addOnSuccessListener { text ->
                                val lines = ArrayList<Line>()
                                for (block in text.textBlocks) {
                                    for (line in block.lines) {
                                        val box = line.boundingBox ?: continue
                                        lines.add(Line(line.text, Rect(box)))
                                    }
                                }
                                onResult(Result(bmp, lines), null)
                            }
                            .addOnFailureListener { e ->
                                onResult(null, "OCR fail: ${e.message}")
                            }
                    }

                    override fun onFailure(errorCode: Int) {
                        onResult(null, "screenshot error $errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            onResult(null, "takeScreenshot exception: ${e.message}")
        }
    }

    /**
     * Slider ke andar safed gol thumb (>> wala) ka center X, pixel se.
     * Sirf slider ke left hisse me dhoondhta hai (text se pehle tak). Nahi mila to null.
     */
    fun findThumbCenterX(bmp: Bitmap, slider: Rect): Int? {
        val y = slider.centerY().coerceIn(0, bmp.height - 1)
        val from = slider.left.coerceAtLeast(0)
        val to = minOf(slider.left + (slider.height() * 1.5f).toInt(), bmp.width - 1)
        var first = -1
        var last = -1
        for (x in from..to) {
            val c = bmp.getPixel(x, y)
            if (Color.red(c) > 225 && Color.green(c) > 225 && Color.blue(c) > 225) {
                if (first < 0) first = x
                last = x
            }
        }
        if (first < 0) return null
        val w = last - first
        if (w < slider.height() * 0.5f || w > slider.height() * 1.3f) return null
        return (first + last) / 2
    }

    private fun isColored(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return (maxOf(r, g, b) - minOf(r, g, b)) > 60
    }

    /**
     * "Accept in 7s" text ke aas-paas ka rangeen (blue/green) slider pill dhoondhta hai.
     * Pixel se dhoondhta hai, isliye photo / camera se dikhi screen par bhi (thoda-bahut) chalta hai.
     * Slider na mile to null.
     */
    fun estimateSlider(bmp: Bitmap, text: Rect): Rect? {
        val w = bmp.width
        val h = bmp.height
        val cy = text.centerY().coerceIn(0, h - 1)
        val refX = (text.right + 24).coerceIn(0, w - 1)
        if (!isColored(bmp.getPixel(refX, cy))) return null

        var top = cy
        while (top > 0 && isColored(bmp.getPixel(refX, top - 1))) top--
        var bottom = cy
        while (bottom < h - 1 && isColored(bmp.getPixel(refX, bottom + 1))) bottom++
        val pillH = bottom - top
        if (pillH < 40) return null

        val gapMax = (pillH * 1.3f).toInt()
        val y = (top + bottom) / 2

        var left = refX
        var last = refX
        var x = refX
        while (x > 0) {
            if (isColored(bmp.getPixel(x, y))) last = x else if (last - x > gapMax) break
            x--
        }
        left = last

        var right = refX
        last = refX
        x = refX
        while (x < w - 1) {
            if (isColored(bmp.getPixel(x, y))) last = x else if (x - last > gapMax) break
            x++
        }
        right = last

        if (right - left < pillH * 3) return null
        return Rect(left, top, right, bottom)
    }
}
