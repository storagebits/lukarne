package app.lukarne.globe

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The globe's static geometry, from assets built by tools/catalog/geo.py (Natural Earth, public domain).
 * Everything is flat float arrays of unit vectors so a frame projects them without allocating.
 */
class GlobeGeometry(
    /** Land dots, (x, y, z) triples. */
    val dots: FloatArray,
    /** Coastline vertices of all chunks, (x, y, z) triples. */
    val coast: FloatArray,
    /** Per chunk: first vertex index into [coast] / 3. */
    val chunkStart: IntArray,
    val chunkCount: IntArray,
    /** Per chunk bounding cap: centre (x, y, z) and sin of its angular radius, 4 floats per chunk. */
    val chunkCap: FloatArray,
) {
    val dotCount get() = dots.size / 3
    val chunks get() = chunkStart.size

    companion object {
        fun load(context: Context): GlobeGeometry {
            val dots = floats(context.assets.open("land_dots.bin").use { it.readBytes() })
            val buf = ByteBuffer.wrap(context.assets.open("coast.bin").use { it.readBytes() })
                .order(ByteOrder.LITTLE_ENDIAN)
            val n = buf.int
            val start = IntArray(n); val count = IntArray(n); val cap = FloatArray(n * 4)
            val verts = FloatArray(buf.remaining() / 4) // upper bound, trimmed below
            var v = 0
            for (i in 0 until n) {
                val k = buf.int
                cap[i * 4] = buf.float; cap[i * 4 + 1] = buf.float; cap[i * 4 + 2] = buf.float
                val cosR = buf.float
                cap[i * 4 + 3] = kotlin.math.sqrt((1f - cosR * cosR).coerceAtLeast(0f))
                start[i] = v / 3
                count[i] = k
                repeat(k * 3) { verts[v++] = buf.float }
            }
            return GlobeGeometry(dots, verts.copyOf(v), start, count, cap)
        }

        private fun floats(bytes: ByteArray): FloatArray {
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }
    }
}
