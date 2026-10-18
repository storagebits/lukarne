package app.lukarne.globe

import app.lukarne.data.Catalog
import app.lukarne.data.Place
import app.lukarne.data.Region
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Dir(val dx: Double, val dy: Double) { UP(0.0, -1.0), DOWN(0.0, 1.0), LEFT(-1.0, 0.0), RIGHT(1.0, 0.0) }

/**
 * Where the focus is on the globe. Two levels: a region (whole-globe view) or a place inside a region (zoomed in).
 * Moves are jumps between targets, never a free pan: the remote has four arrows, not a mouse.
 */
sealed interface GlobeFocus {
    val lat: Double
    val lon: Double

    data class AtRegion(val region: Region) : GlobeFocus {
        override val lat get() = region.lat
        override val lon get() = region.lon
    }

    data class AtPlace(val place: Place) : GlobeFocus {
        override val lat get() = place.lat
        override val lon get() = place.lon
    }
}

class GlobeNav(private val catalog: Catalog) {
    /** Off (default): only regions and places with live video are targets; still-only ones stay dim and skipped. */
    var stills = false

    fun regions(): List<Region> = if (stills) catalog.regions else catalog.regions.filter { catalog.hasLive(it) }
    fun places(regionId: String? = null): List<Place> {
        val all = if (regionId == null) catalog.places else catalog.placesIn(regionId)
        return if (stills) all else all.filter { catalog.hasLive(it) }
    }

    /**
     * The target nearest to [from] in direction [dir], as seen on a map centred on [from] with north up
     * (azimuthal: works across the whole sphere, so RIGHT from Japan reaches Hawaii, then California).
     * Candidates more than 60° off the direction are ignored, then 90° if nothing qualifies.
     */
    fun <T> next(fromLat: Double, fromLon: Double, dir: Dir, candidates: List<T>, pos: (T) -> Pair<Double, Double>): T? {
        for (maxOff in doubleArrayOf(60.0, 89.0)) {
            val cosMax = cos(maxOff * PI / 180)
            var best: T? = null
            var bestScore = Double.MAX_VALUE
            for (c in candidates) {
                val (lat, lon) = pos(c)
                val dist = Sphere.angle(fromLat, fromLon, lat, lon)
                if (dist < 1e-4) continue
                val b = Sphere.bearing(fromLat, fromLon, lat, lon)
                val vx = sin(b); val vy = -cos(b)   // screen space: x right, y down, north up
                val cosOff = vx * dir.dx + vy * dir.dy
                if (cosOff < cosMax) continue
                val score = dist * (1 + 2.5 * (1 - cosOff))
                if (score < bestScore) { bestScore = score; best = c }
            }
            if (best != null) return best
        }
        return null
    }

    fun move(focus: GlobeFocus, dir: Dir): GlobeFocus? = when (focus) {
        is GlobeFocus.AtRegion -> next(focus.lat, focus.lon, dir, regions().filter { it.id != focus.region.id }) {
            it.lat to it.lon
        }?.let { GlobeFocus.AtRegion(it) }

        is GlobeFocus.AtPlace -> {
            // Stay in the region while it has something that way; otherwise travel on to the next region's places,
            // so zoomed-in browsing flows across borders instead of hitting a wall.
            val here = places(focus.place.region).filter { it.id != focus.place.id }
            val near = next(focus.lat, focus.lon, dir, here) { it.lat to it.lon }
            val pick = near ?: next(focus.lat, focus.lon, dir, places().filter { it.region != focus.place.region }) {
                it.lat to it.lon
            }
            pick?.let { GlobeFocus.AtPlace(it) }
        }
    }

    /** The place to land on when diving into [region]: the most live cams (then the most cams). */
    fun entryPlace(region: Region): Place? =
        places(region.id).maxWithOrNull(compareBy<Place>({ it.live }, { it.cams }))

    /**
     * Zoom for a region, as the globe radius in multiples of the whole-globe radius: the region's places spread
     * (85th percentile from its centre) fills about a third of the screen height.
     */
    fun regionZoom(regionId: String): Float {
        val r = catalog.region(regionId) ?: return 1f
        val d = places(regionId).ifEmpty { catalog.placesIn(regionId) }.map { Sphere.angle(r.lat, r.lon, it.lat, it.lon) }.sorted()
        val spread = (if (d.isEmpty()) 0.05 else d[(d.size * 0.85).toInt().coerceAtMost(d.size - 1)])
            .coerceIn(150.0 / 6371, 1600.0 / 6371)
        // Whole-globe radius is GLOBE_FILL of the half-height; we want sin(spread)·R ≈ 0.62 of the half-height.
        // Capped so neighbouring places stay on screen: the arrows should always lead somewhere visible.
        return (0.62 / sin(spread) / GLOBE_FILL).toFloat().coerceIn(1.6f, 6f)
    }

    companion object {
        /** Whole-globe radius as a fraction of half the screen height. */
        const val GLOBE_FILL = 0.84
    }
}
