package dev.jeary.hanami.math

/**
 * An immutable list of points packed as `x0 y0 z0 x1 y1 z1 ...`.
 *
 * Shapes are sampled once into a PointCloud and cached; every tick the renderer only multiplies the
 * packed array by a [Mat3], which is far cheaper than rebuilding vectors.
 */
class PointCloud(private val coords: DoubleArray) {

    init {
        require(coords.size % 3 == 0) { "Packed coordinates must be a multiple of 3, got ${coords.size}" }
    }

    val size: Int get() = coords.size / 3

    fun isEmpty(): Boolean = coords.isEmpty()

    fun x(i: Int): Double = coords[i * 3]
    fun y(i: Int): Double = coords[i * 3 + 1]
    fun z(i: Int): Double = coords[i * 3 + 2]

    operator fun get(i: Int) = Vec3(x(i), y(i), z(i))

    /** Read-only access for the transform hot path. Callers must not mutate the array. */
    internal fun raw(): DoubleArray = coords

    fun toList(): List<Vec3> = List(size) { get(it) }

    fun transformed(m: Mat3, translation: Vec3 = Vec3.ZERO): PointCloud {
        val out = DoubleArray(coords.size)
        m.transformInto(coords, 0, size, out, 0, translation.x, translation.y, translation.z)
        return PointCloud(out)
    }

    operator fun plus(other: PointCloud): PointCloud = PointCloud(coords + other.coords)

    /** Axis-aligned bounds as (min, max); both are [Vec3.ZERO] for an empty cloud. */
    fun bounds(): Pair<Vec3, Vec3> {
        if (isEmpty()) return Vec3.ZERO to Vec3.ZERO
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (i in 0 until size) {
            minX = minOf(minX, x(i)); maxX = maxOf(maxX, x(i))
            minY = minOf(minY, y(i)); maxY = maxOf(maxY, y(i))
            minZ = minOf(minZ, z(i)); maxZ = maxOf(maxZ, z(i))
        }
        return Vec3(minX, minY, minZ) to Vec3(maxX, maxY, maxZ)
    }

    override fun toString() = "PointCloud(size=$size)"

    companion object {
        val EMPTY = PointCloud(DoubleArray(0))

        fun of(points: List<Vec3>): PointCloud {
            val out = DoubleArray(points.size * 3)
            points.forEachIndexed { i, p ->
                out[i * 3] = p.x
                out[i * 3 + 1] = p.y
                out[i * 3 + 2] = p.z
            }
            return PointCloud(out)
        }

        fun concat(clouds: List<PointCloud>): PointCloud = when (clouds.size) {
            0 -> EMPTY
            1 -> clouds[0]
            else -> {
                val out = DoubleArray(clouds.sumOf { it.size } * 3)
                var offset = 0
                for (cloud in clouds) {
                    cloud.coords.copyInto(out, offset)
                    offset += cloud.coords.size
                }
                PointCloud(out)
            }
        }
    }
}
