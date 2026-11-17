package dev.jeary.hanami.math.shape

import dev.jeary.hanami.math.PointCloud
import dev.jeary.hanami.math.Vec3
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.font.FontRenderContext
import java.awt.geom.PathIterator
import java.util.concurrent.ConcurrentHashMap

/**
 * Text turned into particles. The glyph outlines come straight from the font as vector paths
 * (no rasterising), are flattened into polylines, then resampled evenly — so 花 reads as a
 * brush-like outline rather than a pixelated blob.
 *
 * Authored on the XY plane, centred on the origin, [height] blocks tall.
 */
data class Glyph(
    val text: String,
    val font: String,
    val height: Double,
    val points: Int,
) : Shape {

    override fun sample(): PointCloud {
        val awtFont = resolveFont(font, text)
            ?: throw GlyphUnavailableException(text, font)

        val vector = awtFont.deriveFont(Font.PLAIN, RENDER_SIZE)
            .createGlyphVector(FontRenderContext(null, true, true), text)
        val outline = vector.outline
        val bounds = outline.bounds2D
        if (bounds.height <= 0.0) throw GlyphUnavailableException(text, font)

        val scale = height / bounds.height
        val cx = bounds.centerX
        val cy = bounds.centerY

        val paths = mutableListOf<MutableList<Vec3>>()
        var current = mutableListOf<Vec3>()
        val coords = DoubleArray(6)
        val iterator = outline.getPathIterator(null, FLATNESS)
        while (!iterator.isDone) {
            when (iterator.currentSegment(coords)) {
                PathIterator.SEG_MOVETO -> {
                    if (current.size > 1) paths += current
                    current = mutableListOf(toLocal(coords, cx, cy, scale))
                }
                PathIterator.SEG_LINETO -> current += toLocal(coords, cx, cy, scale)
                PathIterator.SEG_CLOSE -> {
                    current.firstOrNull()?.let { current += it }
                    if (current.size > 1) paths += current
                    current = mutableListOf()
                }
            }
            iterator.next()
        }
        if (current.size > 1) paths += current

        return PointCloud.of(Resample.uniform(paths, points))
    }

    // AWT's y axis points down; flip it so text is upright in world space.
    private fun toLocal(c: DoubleArray, cx: Double, cy: Double, scale: Double) =
        Vec3((c[0] - cx) * scale, -(c[1] - cy) * scale, 0.0)

    companion object {
        private const val RENDER_SIZE = 128f
        private const val FLATNESS = 0.6

        private val fontCache = ConcurrentHashMap<String, Font>()

        /**
         * Finds a font able to draw every character of [text]: the requested family first, then
         * any installed font. CJK text needs a CJK font on the host (e.g. Noto Sans CJK).
         */
        fun resolveFont(family: String, text: String): Font? {
            fontCache["$family|$text"]?.let { return it }
            val requested = Font(family, Font.PLAIN, 12)
            val found = if (requested.canDisplayUpTo(text) == -1) requested
            else GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts
                .firstOrNull { it.canDisplayUpTo(text) == -1 }
            if (found != null) fontCache["$family|$text"] = found
            return found
        }
    }
}

class GlyphUnavailableException(text: String, font: String) :
    IllegalStateException("No installed font can draw \"$text\" (requested \"$font\"). Install a font that covers it, e.g. Noto Sans CJK.")
