package app.lukarne.data

import android.content.Context
import app.lukarne.model.StreamKind
import app.lukarne.model.Webcam
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A named area of the globe: the first level of focus. */
@Serializable
data class Region(
    val id: String,
    val en: String,
    val fr: String,
    val es: String = en,
    val lat: Double,
    val lon: Double,
    val tz: String = "UTC",
    val cams: Int,
    val live: Int,
) {
    fun name(locale: Locale) = when (locale.language) { "fr" -> fr; "es" -> es; else -> en }
}

/** A cluster of cams a few tens of km across (a town, a valley): the second level of focus. */
@Serializable
data class Place(
    val id: String,
    val region: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val tz: String = "UTC",
    val cams: Int = 0,
    val live: Int = 0,
)

@Serializable
private data class CatalogFile(
    val version: Int,
    val generated: String,
    val regions: List<Region>,
    val places: List<Place>,
    val cams: List<Webcam>,
)

/**
 * The catalogue built by tools/catalog: embedded in the APK (assets/catalog.json), or a newer copy published daily
 * and downloaded by AppGraph. Immutable; liveness lives in StreamHealth.
 */
class Catalog private constructor(f: CatalogFile) {
    val generated = f.generated
    val regions: List<Region> = f.regions
    val places: List<Place> = f.places
    val cams: List<Webcam> = f.cams

    private val camsByPlace = cams.groupBy { it.cluster }
    private val placesByRegion = places.groupBy { it.region }
    private val regionById = regions.associateBy { it.id }
    private val placeById = places.associateBy { it.id }

    fun region(id: String) = regionById[id]
    fun place(id: String) = placeById[id]
    fun placesIn(regionId: String): List<Place> = placesByRegion[regionId].orEmpty()
    fun camsAt(placeId: String): List<Webcam> = camsByPlace[placeId].orEmpty()
    fun camCount(placeId: String) = camsByPlace[placeId]?.size ?: 0

    private val liveCams = cams.filter { it.isLive }

    /** True when [place] (or [region]) has live video: the only targets on the globe unless stills are on. */
    fun hasLive(place: Place) = place.live > 0
    fun hasLive(region: Region) = region.live > 0

    /**
     * The cams of a mosaic for [place], [isDead] ones left out.
     *
     * Live first (default): the live cams of the place's region plus those across its border within [NEIGHBOUR_KM],
     * nearest first — so a wall is video, not stills, and stays local even when that makes it short. With [withStills], the place's own cams come first
     * (live first) and its region's nearest cams of any kind fill the rest, as before.
     */
    fun zone(place: Place, size: Int, withStills: Boolean, isDead: (Webcam) -> Boolean): List<Webcam> {
        if (!withStills) {
            // Never pad the wall with far-away cams: a short wall of nearby video beats London next to Patagonia.
            return liveCams.asSequence().filterNot(isDead)
                .map { it to km(place.lat, place.lon, it.lat, it.lon) }
                .filter { (cam, d) -> d <= NEIGHBOUR_KM || placeById[cam.cluster]?.region == place.region }
                .sortedBy { it.second }
                .take(size * 2)
                .map { it.first }
                .toList()
        }
        val own = camsAt(place.id).filterNot(isDead).sortedBy { liveRank(it) }
        if (own.size >= size) return own
        val more = placesIn(place.region).asSequence()
            .filter { it.id != place.id }
            .flatMap { camsAt(it.id) }
            .filterNot(isDead)
            .sortedWith(compareBy({ km(place.lat, place.lon, it.lat, it.lon) / if (it.isLive) 3 else 1 }))
            .take(size * 3 - own.size)
            .toList()
        return own + more
    }

    private fun liveRank(c: Webcam) = when (c.kind) {
        StreamKind.YOUTUBE -> 0
        StreamKind.HLS -> 1
        StreamKind.IMAGE -> 2
    }

    companion object {
        /** How far a live-only wall reaches across its region's border (Boston → Québec, London → Paris). */
        const val NEIGHBOUR_KM = 800.0

        private val json = Json { ignoreUnknownKeys = true }

        /** The schema this build reads; a published catalogue with another version is ignored. */
        const val VERSION = 1

        fun fromAsset(context: Context): Catalog =
            context.assets.open("catalog.json").use { parse(it.readBytes().decodeToString()) }

        /** Throws on anything that isn't a usable catalogue of this schema (truncated download, newer format). */
        fun parse(text: String): Catalog {
            val f = json.decodeFromString<CatalogFile>(text)
            require(f.version == VERSION) { "catalogue version ${f.version}, this build reads $VERSION" }
            require(f.cams.isNotEmpty() && f.places.isNotEmpty()) { "empty catalogue" }
            return Catalog(f)
        }

        fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 6371 * 2 * asin(sqrt(a))
        }
    }
}
