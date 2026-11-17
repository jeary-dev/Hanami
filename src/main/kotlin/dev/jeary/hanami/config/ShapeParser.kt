package dev.jeary.hanami.config

import dev.jeary.hanami.math.Vec3
import dev.jeary.hanami.math.shape.Axis
import dev.jeary.hanami.math.shape.BezierPath
import dev.jeary.hanami.math.shape.BezierSegment
import dev.jeary.hanami.math.shape.Circle
import dev.jeary.hanami.math.shape.Glyph
import dev.jeary.hanami.math.shape.Helix
import dev.jeary.hanami.math.shape.Line
import dev.jeary.hanami.math.shape.Lissajous
import dev.jeary.hanami.math.shape.Mirror
import dev.jeary.hanami.math.shape.Plane
import dev.jeary.hanami.math.shape.PointShape
import dev.jeary.hanami.math.shape.Polyline
import dev.jeary.hanami.math.shape.Radial
import dev.jeary.hanami.math.shape.Shape
import dev.jeary.hanami.math.shape.Sphere
import dev.jeary.hanami.math.shape.Spiral
import dev.jeary.hanami.math.shape.Transform
import dev.jeary.hanami.math.shape.Union
import dev.jeary.hanami.math.shape.WithFallback

/**
 * Turns a `shape:` section into a [Shape]. Shapes nest (`mirror`, `union`, `transform`,
 * `radial`), so this is a small recursive-descent parser over YAML sections.
 */
object ShapeParser {

    val KINDS = listOf(
        "point", "line", "polyline", "circle", "spiral", "helix", "lissajous",
        "sphere", "bezier", "glyph", "mirror", "union", "transform", "radial",
    )

    private val POINTS = 1..2_000
    private val SIZE = 0.01..32.0
    private const val MAX_DEPTH = 8

    fun parse(node: ConfigNode, depth: Int = 0): Shape? {
        if (depth > MAX_DEPTH) {
            node.error(null, "shapes are nested more than $MAX_DEPTH levels deep")
            return null
        }
        val kind = node.requireString("kind")?.lowercase() ?: return null
        val plane = node.choice("plane", Plane.XZ, Plane.entries.map { it.name.lowercase() }, Plane::byId)

        return when (kind) {
            "point" -> PointShape

            "line" -> Line(
                from = node.vec3("from", Vec3.ZERO),
                to = node.vec3("to", Vec3.UP),
                points = node.int("points", 8, POINTS),
            )

            "polyline" -> {
                val vertices = node.rawList("vertices").orEmpty().mapIndexedNotNull { i, raw ->
                    ConfigNode.parseVec(raw, allow2d = true)?.let { v -> if (raw is List<*> && raw.size == 2) plane.place(v.x, v.y) else v }
                        ?: null.also { node.error("vertices[$i]", "expected [x, y, z] or [u, v]") }
                }
                if (vertices.size < 2) {
                    node.error("vertices", "a polyline needs at least two vertices")
                    null
                } else Polyline(vertices, node.int("points", 24, POINTS))
            }

            "circle" -> Circle(
                radius = node.double("radius", 1.0, SIZE),
                points = node.int("points", 24, POINTS),
                plane = plane,
                arcDegrees = node.double("arc", 360.0, 1.0..360.0),
            )

            "spiral" -> Spiral(
                turns = node.double("turns", 2.0, 0.1..64.0),
                radiusStart = node.double("radius-start", 0.1, 0.0..32.0),
                radiusEnd = node.double("radius-end", 1.0, 0.0..32.0),
                height = node.double("height", 0.0, -32.0..32.0),
                points = node.int("points", 32, POINTS),
                plane = plane,
            )

            "helix" -> Helix(
                radius = node.double("radius", 0.8, SIZE),
                height = node.double("height", 2.0, -32.0..32.0),
                turns = node.double("turns", 2.0, 0.1..64.0),
                strands = node.int("strands", 1, 1..16),
                points = node.int("points", 32, POINTS),
            )

            "lissajous" -> Lissajous(
                a = node.double("a", 1.0, 0.0..32.0),
                b = node.double("b", 2.0, 0.0..32.0),
                c = node.double("c", 0.0, 0.0..32.0),
                deltaDegrees = node.double("delta", 90.0, -360.0..360.0),
                amplitude = node.vec3("amplitude", Vec3(1.0, 0.2, 1.0)),
                points = node.int("points", 48, POINTS),
            )

            "sphere" -> Sphere(
                radius = node.double("radius", 1.0, SIZE),
                points = node.int("points", 32, POINTS),
                shell = node.bool("shell", true),
            )

            "bezier" -> parseBezier(node, plane)

            "glyph" -> {
                val text = node.requireString("text")
                if (text.isNullOrBlank()) {
                    if (text != null) node.error("text", "text must not be empty")
                    null
                } else {
                    val glyph = Glyph(
                        text = text,
                        font = node.string("font", "Serif"),
                        height = node.double("size", 1.0, SIZE),
                        points = node.int("points", 90, POINTS),
                    )
                    val fallback = node.node("fallback")?.let { parse(it, depth + 1) }
                    if (fallback != null) WithFallback(glyph, fallback) else glyph
                }
            }

            "mirror" -> node.node("shape", required = true)?.let { parse(it, depth + 1) }?.let {
                Mirror(node.choice("axis", Axis.X, Axis.entries.map { a -> a.name.lowercase() }) { raw ->
                    Axis.entries.firstOrNull { a -> a.name.equals(raw, true) }
                }, it)
            }

            "union" -> {
                val children = node.nodeList("shapes").mapNotNull { parse(it, depth + 1) }
                if (children.isEmpty()) {
                    node.error("shapes", "a union needs at least one shape")
                    null
                } else Union(children)
            }

            "transform" -> node.node("shape", required = true)?.let { parse(it, depth + 1) }?.let {
                Transform(
                    shape = it,
                    translate = node.vec3("translate", Vec3.ZERO),
                    rotate = node.vec3("rotate", Vec3.ZERO),
                    scale = node.double("scale", 1.0, 0.01..32.0),
                )
            }

            "radial" -> {
                val children = node.nodeList("shapes").mapNotNull { parse(it, depth + 1) }
                    .ifEmpty { listOfNotNull(node.node("shape")?.let { parse(it, depth + 1) }) }
                if (children.isEmpty()) {
                    node.error("shapes", "a radial needs `shape:` or a `shapes:` list")
                    null
                } else Radial(
                    radius = node.double("radius", 1.0, SIZE),
                    count = node.int("count", children.size, 1..64),
                    shapes = children,
                )
            }

            else -> null.also { node.error("kind", "unknown shape \"$kind\" (expected one of: ${KINDS.joinToString()})") }
        }
    }

    /**
     * `segments:` is a list of 4-point lists `[p0, c1, c2, p1]`. Points may be 2D (placed on
     * `plane:`, which defaults to XY for beziers) or full 3D.
     */
    private fun parseBezier(node: ConfigNode, defaultPlane: Plane): Shape? {
        val plane = if (node.has("plane")) defaultPlane else Plane.XY
        val raw = node.rawList("segments")
        if (raw.isNullOrEmpty()) {
            node.error("segments", "a bezier needs at least one segment: [[x,y], [x,y], [x,y], [x,y]]")
            return null
        }
        val segments = raw.mapIndexedNotNull { i, entry ->
            val pts = (entry as? List<*>)?.map { p ->
                val v = ConfigNode.parseVec(p, allow2d = true) ?: return@map null
                if (p is List<*> && p.size == 2) plane.place(v.x, v.y) else v
            }
            if (pts == null || pts.size != 4 || pts.any { it == null }) {
                node.error("segments[$i]", "expected four points [start, control1, control2, end]")
                null
            } else {
                BezierSegment(requireNotNull(pts[0]), requireNotNull(pts[1]), requireNotNull(pts[2]), requireNotNull(pts[3]))
            }
        }
        if (segments.isEmpty()) return null
        return BezierPath(segments, node.int("points", 48, POINTS))
    }
}
