package io.github.gamesbyian.chunks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test-only renderer for experimental CFFs. These OTFs are staged in the
 * debug-only APK's assets by dedicated CI, never in the release app.
 *
 * Native Android TextView measurements and actual Bitmap/Canvas pixels are
 * sampled at 1x and 2x logical text size, with the same finite panel width.
 * These are not Compose integration, TalkBack operation or visual approval.
 */
@RunWith(AndroidJUnit4::class)
class ExperimentalTypefaceNativeInstrumentedTest {
    private val styles = listOf("Regular", "Medium", "Semibold", "Bold", "ExtraBold")
    private val samples = linkedMapOf(
        "long_filename" to "Night.Court.S01E01.1984.Restored.2160p.WEBRip.Multi-Language.[Český-přehled].mkv",
        "stacked_marks" to "Ǖ ǖ Ǘ ǘ Ǚ ǚ Ǜ ǜ Ǻ ǻ Ȫ ȫ ḕ ḗ ḭ ḯ",
        "transfer_stats" to "Downloads [156] • 68.7% • 2.45 MB/s • Résumé … It’s working!"
    )

    @Test
    fun allFiveCandidateFacesRenderWithCompleteUnellipsizedTextAtNormalAndDoubleScale() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val assets = context.assets
        // Other Android workflows deliberately do not stage the experimental
        // faces. They may compile this test but must skip its data-dependent
        // fixture, rather than fail unrelated Add/download UI test suites.
        assumeTrue("Experimental debug font fixtures not staged",
            assets.list("referenceFonts")?.count { it.endsWith("-Stacked.otf") } == 5)
        val dir = File(context.filesDir, "font-acceptance")
        assertTrue("Native font review output directory", dir.isDirectory || dir.mkdirs())
        val density = context.resources.displayMetrics.density
        val width = (340f * density).toInt().coerceAtLeast(300)
        var count = 0
        for (style in styles) {
            val fileName = "ChunksReferencePanel-${style}-Stacked.otf"
            val font = Typeface.createFromAsset(assets, "referenceFonts/$fileName")
            for (factor in listOf(1f, 2f)) {
                for ((sampleId, text) in samples) {
                    val view = TextView(context).apply {
                        this.text = text
                        typeface = font
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * density * factor)
                        setTextColor(Color.BLACK)
                        setBackgroundColor(Color.WHITE)
                        setPadding(12, 8, 12, 8)
                        setSingleLine(false)
                        ellipsize = null
                        contentDescription = text
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        includeFontPadding = true
                    }
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    val height = view.measuredHeight
                    assertTrue("$style $factor $sampleId: invalid height $height",
                        height in 1..3000)
                    view.layout(0, 0, width, height)
                    val lines = view.layout
                    assertTrue("$style $factor $sampleId: no rendered lines",
                        lines != null && lines.lineCount > 0)
                    assertEquals("$style $factor $sampleId: last glyph clipped/truncated",
                        text.length, lines!!.getLineEnd(lines.lineCount - 1))
                    assertEquals("$style $factor $sampleId: accessibility label differs",
                        text, view.contentDescription.toString())
                    val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(image)
                    view.draw(canvas)
                    // Verify nonempty real ink without assuming particular antialiasing.
                    val pixels = IntArray(width * height)
                    image.getPixels(pixels, 0, width, 0, 0, width, height)
                    assertTrue("$style $factor $sampleId: only blank pixels",
                        pixels.any { it != Color.WHITE })
                    val sizeName = if (factor == 1f) "100pct" else "200pct"
                    val output = File(dir, "chunks-stacked-$style-$sizeName-$sampleId.png")
                    FileOutputStream(output).use { stream ->
                        assertTrue("Bitmap PNG failed", image.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    }
                    assertTrue("Screenshot missing", output.isFile && output.length() > 200)
                    image.recycle()
                    count++
                }
            }
        }
        assertEquals(30, count)
    }
}
