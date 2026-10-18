package app.lukarne.globe

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import app.lukarne.data.Place
import app.lukarne.data.Region
import app.lukarne.ui.theme.Fonts
import app.lukarne.ui.theme.Palette
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * The few things drawn over the GL globe with Compose: the focus ring and the labels. Same orthographic
 * projection as the shaders, on the CPU, for a few dozen points per frame.
 */
class GlobeOverlay(private val measurer: TextMeasurer) {
    private val labelCache = HashMap<String, TextLayoutResult>()
    private val placed = ArrayList<FloatArray>()
    private val tmp = FloatArray(3)

    private var ex = 0.0; private var ey = 0.0
    private var nx = 0.0; private var ny = 0.0; private var nz = 0.0
    private var ux = 0.0; private var uy = 0.0; private var uz = 0.0
    private var cx = 0f; private var cy = 0f; private var r = 0f

    fun setCamera(lat: Double, lon: Double, zoom: Float, centre: Offset, height: Float) {
        cx = centre.x; cy = centre.y
        r = height / 2 * GlobeNav.GLOBE_FILL.toFloat() * zoom
        val lat0 = Sphere.rad(lat); val lon0 = Sphere.rad(lon)
        ex = -sin(lon0); ey = cos(lon0)
        nx = -sin(lat0) * cos(lon0); ny = -sin(lat0) * sin(lon0); nz = cos(lat0)
        ux = cos(lat0) * cos(lon0); uy = cos(lat0) * sin(lon0); uz = sin(lat0)
    }

    /** Screen position (x, y, depth) of a point, or false when it is behind the globe. */
    fun project(lat: Double, lon: Double, out: FloatArray): Boolean {
        val px = Sphere.x(lat, lon); val py = Sphere.y(lat, lon); val pz = Sphere.z(lat)
        val d = px * ux + py * uy + pz * uz
        if (d <= 0.02) return false
        out[0] = cx + r * (px * ex + py * ey).toFloat()
        out[1] = cy - r * (px * nx + py * ny + pz * nz).toFloat()
        out[2] = d.toFloat()
        return true
    }

    fun draw(scope: DrawScope, focus: GlobeFocus, regions: List<Region>, places: List<Place>, pulse: Float, locale: Locale) =
        with(scope) {
            placed.clear()
            drawRing(focus, pulse)
            when (focus) {
                is GlobeFocus.AtRegion -> drawRegionLabels(regions, focus, locale)
                is GlobeFocus.AtPlace -> drawPlaceLabels(places, focus)
            }
        }

    private fun DrawScope.drawRing(focus: GlobeFocus, pulse: Float) {
        if (!project(focus.lat, focus.lon, tmp)) return
        val c = Offset(tmp[0], tmp[1])
        val radius = when (focus) {
            is GlobeFocus.AtRegion -> 34f + 4f * pulse
            is GlobeFocus.AtPlace -> 22f + 3f * pulse
        }
        drawCircle(Palette.LightHot.copy(alpha = 0.85f), radius = radius, center = c, style = Stroke(2.2f))
        drawCircle(Palette.LightHot.copy(alpha = 0.18f * (1 - pulse)), radius = radius + 10f * pulse, center = c, style = Stroke(6f))
        placed += floatArrayOf(c.x - radius, c.y - radius, c.x + radius, c.y + radius)
    }

    private fun DrawScope.drawRegionLabels(regions: List<Region>, focus: GlobeFocus.AtRegion, locale: Locale) {
        for (reg in regions.sortedByDescending { it.cams }) {
            if (reg.id == focus.region.id) continue // the side panel names it; the ring marks it
            if (!project(reg.lat, reg.lon, tmp) || tmp[2] < 0.55f) continue
            val layout = label(reg.name(locale).uppercase(locale), small = true)
            val x = tmp[0] - layout.size.width / 2f
            val y = tmp[1] + 16f
            if (!reserve(x, y, layout.size.width.toFloat(), layout.size.height.toFloat())) continue
            drawText(layout, color = Palette.FgMuted.copy(alpha = 0.42f * tmp[2]), topLeft = Offset(x, y))
        }
    }

    private fun DrawScope.drawPlaceLabels(places: List<Place>, focus: GlobeFocus.AtPlace) {
        // Name the neighbours the arrows can reach: the closest places, a few at most.
        val near = places.asSequence()
            .filter { it.id != focus.place.id }
            .sortedBy { Sphere.angle(focus.lat, focus.lon, it.lat, it.lon) }
            .take(10)
        for (p in near) {
            if (!project(p.lat, p.lon, tmp)) continue
            val layout = label(p.name, small = false)
            val x = tmp[0] + 12f
            val y = tmp[1] - layout.size.height / 2f
            if (x + layout.size.width > size.width || y < 0 || y > size.height) continue
            if (!reserve(x, y, layout.size.width.toFloat(), layout.size.height.toFloat())) continue
            drawText(layout, color = Palette.FgMuted.copy(alpha = 0.75f), topLeft = Offset(x, y))
        }
    }

    /** Greedy collision avoidance: true (and reserves the box) when it overlaps nothing placed this frame. */
    private fun reserve(x: Float, y: Float, w: Float, h: Float): Boolean {
        val pad = 6f
        for (b in placed) if (x < b[2] + pad && x + w > b[0] - pad && y < b[3] + pad && y + h > b[1] - pad) return false
        placed += floatArrayOf(x, y, x + w, y + h)
        return true
    }

    private fun label(text: String, small: Boolean): TextLayoutResult = labelCache.getOrPut(if (small) "s:$text" else text) {
        measurer.measure(
            text,
            if (small) TextStyle(fontFamily = Fonts.Inter, fontSize = 11.sp, letterSpacing = 1.2.sp)
            else TextStyle(fontFamily = Fonts.Serif, fontSize = 17.sp),
        )
    }
}
