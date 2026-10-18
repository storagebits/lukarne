package app.lukarne.globe

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Unit vectors on the globe: x toward (0°, 0°), y toward (0°, 90°E), z toward the North Pole. */
object Sphere {
    fun x(lat: Double, lon: Double) = cos(rad(lat)) * cos(rad(lon))
    fun y(lat: Double, lon: Double) = cos(rad(lat)) * sin(rad(lon))
    fun z(lat: Double) = sin(rad(lat))

    fun rad(deg: Double) = deg * PI / 180
    fun deg(rad: Double) = rad * 180 / PI

    /** Great-circle angle between two points, radians. */
    fun angle(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val d = x(lat1, lon1) * x(lat2, lon2) + y(lat1, lon1) * y(lat2, lon2) + z(lat1) * z(lat2)
        return acos(d.coerceIn(-1.0, 1.0))
    }

    /** Initial bearing from point 1 to point 2, radians clockwise from north. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1); val p2 = rad(lat2); val dl = rad(lon2 - lon1)
        return atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))
    }

    /** The point where the sun is overhead (declination, longitude), good to a fraction of a degree. */
    fun subsolar(now: Instant = Instant.now()): Pair<Double, Double> {
        val t = now.atOffset(ZoneOffset.UTC)
        val day = t.dayOfYear + (t.hour + t.minute / 60.0) / 24.0
        val g = 2 * PI / 365.0 * (day - 1)
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val eqTimeMin = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) -
            0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val utcMin = t.hour * 60 + t.minute + t.second / 60.0
        val lon = -(utcMin + eqTimeMin - 720) / 4.0
        return deg(decl) to wrapLon(lon)
    }

    /** True when the sun is above the horizon (with civil twilight margin) at that point. */
    fun isDaylight(lat: Double, lon: Double, sun: Pair<Double, Double>): Boolean =
        x(lat, lon) * x(sun.first, sun.second) + y(lat, lon) * y(sun.first, sun.second) + z(lat) * z(sun.first) > -0.1

    fun wrapLon(lon: Double): Double {
        var l = (lon + 180) % 360
        if (l < 0) l += 360
        return l - 180
    }

    fun latOf(x: Double, y: Double, z: Double) = deg(asin(z.coerceIn(-1.0, 1.0)))
    fun lonOf(x: Double, y: Double) = deg(atan2(y, x))
    fun norm(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)
}
