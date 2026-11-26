// ===== REPLACE (purani file hatao, ye daalo) =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/ScreenOcrScanner.kt
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

    fun warmUp() {
        recognizer
    }

    /** Pehle se bane bitmap (live screen frame) par OCR. */
    fun recognize(bmp: Bitmap, onResult: (Result?, String?) -> Unit) {
        recognizeRegion(bmp, 0, bmp.height, onResult)
    }

    /** Sirf popup card wale vertical slice par tez OCR; line boxes wapas full-screen coordinates me offset hote hain. */
    fun recognizeRegion(bmp: Bitmap, cropTop: Int, cropBottom: Int, onResult: (Result?, String?) -> Unit) {
        val top = cropTop.coerceIn(0, (bmp.height - 1).coerceAtLeast(0))
        val bottom = cropBottom.coerceIn(top + 1, bmp.height)
        val useCrop = (top > 0 || bottom < bmp.height) && (bottom - top) >= 80
        val targetBmp = if (useCrop) {
            try {
                Bitmap.createBitmap(bmp, 0, top, bmp.width, bottom - top)
            } catch (_: Exception) {
                bmp
            }
        } else {
            bmp
        }
        val offsetY = if (targetBmp !== bmp) top else 0

        recognizer.process(InputImage.fromBitmap(targetBmp, 0))
            .addOnSuccessListener { text ->
                if (targetBmp !== bmp) {
                    try { targetBmp.recycle() } catch (_: Exception) {}
                }
                val lines = ArrayList<Line>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val shifted = Rect(box)
                        if (offsetY != 0) shifted.offset(0, offsetY)
                        lines.add(Line(line.text, shifted))
                    }
                }
                onResult(Result(bmp, lines), null)
            }
            .addOnFailureListener { e ->
                if (targetBmp !== bmp) {
                    try { targetBmp.recycle() } catch (_: Exception) {}
                }
                onResult(null, "OCR fail: ${e.message}")
            }
    }

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
                        val hwBmp = try {
                            Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        } catch (_: Exception) {
                            null
                        }
                        val bmp: Bitmap? = try {
                            hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (_: Exception) {
                            null
                        } finally {
                            try { hwBmp?.recycle() } catch (_: Exception) {}
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
                                try { bmp.recycle() } catch (_: Exception) {}
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
     * Slider ke left 48% hisse me dhoondhta hai aur center ke white circle ko accurately pakadta hai.
     */
    fun findThumbCenterX(bmp: Bitmap, slider: Rect): Int? {
        val pillH = slider.height().coerceAtLeast(1)
        val from = slider.left.coerceAtLeast(0)
        val to = minOf(slider.left + (slider.width() * 0.48f).toInt(), bmp.width - 1)
        if (to <= from) return null

        val yCandidates = intArrayOf(
            slider.centerY().coerceIn(0, bmp.height - 1),
            (slider.centerY() - pillH / 6).coerceIn(0, bmp.height - 1),
            (slider.centerY() + pillH / 6).coerceIn(0, bmp.height - 1)
        )
        val maxInnerGap = (pillH * 0.28f).toInt().coerceAtLeast(4)

        for (y in yCandidates) {
            var bestStart = -1
            var bestEnd = -1
            var curStart = -1
            var curEnd = -1
            for (x in from..to) {
                val c = bmp.getPixel(x, y)
                val isWhite = Color.red(c) > 215 && Color.green(c) > 215 && Color.blue(c) > 215
                if (isWhite) {
                    if (curStart < 0 || x - curEnd > maxInnerGap) {
                        if (curStart >= 0 && (curEnd - curStart) > (bestEnd - bestStart)) {
                            bestStart = curStart
                            bestEnd = curEnd
                        }
                        curStart = x
                    }
                    curEnd = x
                }
            }
            if (curStart >= 0 && (curEnd - curStart) > (bestEnd - bestStart)) {
                bestStart = curStart
                bestEnd = curEnd
            }
            if (bestStart >= 0) {
                val w = bestEnd - bestStart
                if (w >= pillH * 0.45f && w <= pillH * 1.45f) {
                    return (bestStart + bestEnd) / 2
                }
            }
        }
        return null
    }

    private fun isColored(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return (maxOf(r, g, b) - minOf(r, g, b)) > 45
    }

    /**
     * "Accept in 7s" text ke aas-paas ka rangeen (blue/green) slider pill dhoondhta hai.
     * Pixel se dhoondhta hai, isliye photo / camera se dikhi screen par bhi chalta hai.
     * Slider na mile to null.
     */
    fun estimateSlider(bmp: Bitmap, text: Rect): Rect? {
        val w = bmp.width
        val h = bmp.height
        val cy = text.centerY().coerceIn(0, h - 1)
        val probeXs = intArrayOf(
            (text.right + 24).coerceIn(0, w - 1),
            (text.left - 24).coerceIn(0, w - 1),
            text.centerX().coerceIn(0, w - 1)
        )
        var refX = -1
        var refY = cy
        for (px in probeXs) {
            if (isColored(bmp.getPixel(px, cy))) {
                refX = px
                break
            }
        }
        if (refX < 0) {
            val aboveY = (text.top - 10).coerceIn(0, h - 1)
            val belowY = (text.bottom + 10).coerceIn(0, h - 1)
            val cx = text.centerX().coerceIn(0, w - 1)
            if (isColored(bmp.getPixel(cx, aboveY))) {
                refX = cx
                refY = aboveY
            } else if (isColored(bmp.getPixel(cx, belowY))) {
                refX = cx
                refY = belowY
            }
        }
        if (refX < 0) return null

        var top = refY
        while (top > 0 && isColored(bmp.getPixel(refX, top - 1))) top--
        var bottom = refY
        while (bottom < h - 1 && isColored(bmp.getPixel(refX, bottom + 1))) bottom++
        val pillH = bottom - top
        if (pillH < 26) return null

        val gapMax = (pillH * 1.35f).toInt()
        val y = (top + bottom) / 2

        var last = refX
        var x = refX
        while (x > 0) {
            if (isColored(bmp.getPixel(x, y))) last = x else if (last - x > gapMax) break
            x--
        }
        val left = last

        last = refX
        x = refX
        while (x < w - 1) {
            if (isColored(bmp.getPixel(x, y))) last = x else if (x - last > gapMax) break
            x++
        }
        val right = last

        if (right - left < pillH * 2.2f) return null
        return Rect(left, top, right, bottom)
    }
}
