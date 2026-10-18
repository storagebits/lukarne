package app.lukarne.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a webcam is shown. The rest of the app never looks at [Webcam.source]. */
@Serializable
enum class StreamKind {
    /** A live HLS playlist, played by Media3. [Webcam.stream] is the `.m3u8` URL. */
    @SerialName("hls") HLS,

    /** A YouTube live, played by the IFrame player. [Webcam.stream] is the video id. */
    @SerialName("youtube") YOUTUBE,

    /** A still refreshed every few seconds. [Webcam.stream] is the image URL. */
    @SerialName("image") IMAGE,
}

@Serializable
data class Webcam(
    val id: String,
    val source: String,
    val lat: Double,
    val lon: Double,
    val place: String,
    /** ISO 3166-1 alpha-2. */
    val country: String,
    /** IANA zone, e.g. `America/Denver`. */
    val tz: String,
    val kind: StreamKind,
    val stream: String,
    /** A still that represents the cam while the live view loads (or instead of it). */
    val thumb: String? = null,
    /** The place on the globe this cam belongs to ([Place.id], filled by the catalogue pipeline). */
    val cluster: String = "",
    /** Town or area, used to name places. */
    val locality: String = "",
) {
    val isLive: Boolean get() = kind != StreamKind.IMAGE
}
