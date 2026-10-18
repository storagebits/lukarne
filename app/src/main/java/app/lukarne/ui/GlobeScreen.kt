package app.lukarne.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lukarne.LocalGraph
import app.lukarne.R
import androidx.compose.runtime.collectAsState
import app.lukarne.data.Catalog
import app.lukarne.globe.Dir
import app.lukarne.globe.GlobeFocus
import app.lukarne.globe.GlobeGeometry
import app.lukarne.globe.GlobeNav
import app.lukarne.globe.GlobeOverlay
import app.lukarne.globe.gl.GlobeCamera
import app.lukarne.globe.gl.GlobeGl
import app.lukarne.globe.gl.GlSurface
import androidx.compose.ui.viewinterop.AndroidView
import app.lukarne.globe.Sphere
import app.lukarne.ui.theme.Palette
import app.lukarne.ui.theme.Type
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import kotlin.math.pow
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.CubicBezierEasing

/**
 * Home: the globe. Arrows jump the focus between regions (whole globe) or places (zoomed in); OK dives one level,
 * BACK climbs one. The camera always flies to the focused target, so the remote never pans blindly.
 */
@Composable
fun GlobeScreen(
    catalog: Catalog,
    geometry: GlobeGeometry,
    focus: GlobeFocus,
    onFocus: (GlobeFocus) -> Unit,
    onOpenPlace: (app.lukarne.data.Place) -> Unit,
    onSurprise: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
    surfacing: Boolean = false,
    onSurfaced: () -> Unit = {},
) {
    val stills = LocalGraph.current.settings.settings.collectAsState().value.stills
    val nav = remember(catalog) { GlobeNav(catalog) }
    nav.stills = stills
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val overlay = remember { GlobeOverlay(measurer) }
    val glCamera = remember { GlobeCamera() }
    val locale = LocalAppLocale.current

    fun zoomOf(f: GlobeFocus) = when (f) {
        is GlobeFocus.AtRegion -> 1f
        is GlobeFocus.AtPlace -> nav.regionZoom(f.place.region)
    }

    // Camera: starts on the focus and flies whenever the focus changes.
    val lat = remember { Animatable(focus.lat.toFloat()) }
    val lon = remember { Animatable(focus.lon.toFloat()) }
    val zoom = remember { Animatable(zoomOf(focus)) }
    LaunchedEffect(focus) {
        val spec = tween<Float>(850, easing = FastOutSlowInEasing)
        val targetLon = lon.value + Sphere.wrapLon(focus.lon - lon.value).toFloat()   // the short way round
        launch { lat.animateTo(focus.lat.toFloat(), spec) }
        launch { lon.animateTo(targetLon, spec) }
        launch { zoom.animateTo(zoomOf(focus), tween(900, easing = FastOutSlowInEasing)) }
    }

    // The dive (OK on a place) and its reverse: 0 = the normal view, 1 = DIVE_ZOOM times closer, the place centred.
    // Zoom grows geometrically with it, so the fall feels like a constant-speed plunge rather than a slowing one.
    val dive = remember { Animatable(if (surfacing) 1f else 0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (surfacing) {
            // Climb only once the globe is on screen, or the first half of the climb happens behind a blank surface.
            val t0 = System.nanoTime()
            while (glCamera.drawn < 2 && System.nanoTime() - t0 < 1_500_000_000L) withFrameNanos { }
            dive.animateTo(0f, tween(SURFACE_MS, easing = CubicBezierEasing(0.15f, 0.75f, 0.25f, 1f)))
            onSurfaced()
        }
    }
    fun diveInto(place: app.lukarne.data.Place) {
        if (dive.isRunning) return
        scope.launch {
            launch { delay((DIVE_MS * 0.72f).toLong()); onOpenPlace(place) }   // the wall rises out of the end of the fall
            dive.animateTo(1f, tween(DIVE_MS, easing = CubicBezierEasing(0.4f, 0f, 0.75f, 0.6f)))
        }
    }
    val chromeAlpha = { 1f - (dive.value * 2.5f).coerceIn(0f, 1f) }

    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "p",
    )
    val now = rememberMinuteClock()
    val sun = remember(now / 600_000) { Sphere.subsolar() }

    val requester = remember { FocusRequester() }
    TakeRemote(requester)
    Box(
        Modifier.fillMaxSize().background(Palette.Ink)
            .focusRequester(requester)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (dive.isRunning) return@onPreviewKeyEvent true   // mid-dive: the remote waits for the landing
                val dir = when (e.key) {
                    Key.DirectionUp -> Dir.UP
                    Key.DirectionDown -> Dir.DOWN
                    Key.DirectionLeft -> Dir.LEFT
                    Key.DirectionRight -> Dir.RIGHT
                    else -> null
                }
                when {
                    dir != null -> { nav.move(focus, dir)?.let(onFocus); true }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                        when (focus) {
                            is GlobeFocus.AtRegion -> nav.entryPlace(focus.region)?.let { onFocus(GlobeFocus.AtPlace(it)) }
                            is GlobeFocus.AtPlace -> diveInto(focus.place)
                        }
                        true
                    }
                    e.key == Key.Back || e.key == Key.Escape -> {
                        when (focus) {
                            is GlobeFocus.AtPlace -> catalog.region(focus.place.region)?.let { onFocus(GlobeFocus.AtRegion(it)) }
                            is GlobeFocus.AtRegion -> if (!app.lukarne.Autopilot.active) onExit()   // Root asks first
                        }
                        true
                    }
                    e.key == Key.MediaPlayPause || e.key == Key.MediaPlay -> { onSurprise(); true }
                    e.key == Key.Menu -> { onSettings(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // The globe itself renders in GL on its own thread; this Canvas feeds it the camera every frame and
        // draws the ring and labels on top.
        AndroidView(
            factory = { ctx -> GlSurface(ctx, GlobeGl(geometry, catalog, glCamera)) },
            modifier = Modifier.fillMaxSize(),
        )
        val focusRegion = remember(focus) {
            val id = when (focus) { is GlobeFocus.AtRegion -> focus.region.id; is GlobeFocus.AtPlace -> focus.place.region }
            catalog.regions.indexOfFirst { it.id == id }
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = chromeAlpha() }) {
            val d = dive.value
            val la = lat.value.toDouble(); val lo = lon.value.toDouble()
            val z = zoom.value * DIVE_ZOOM.pow(d)
            val cx = CENTRE_X + (0.5f - CENTRE_X) * (d * 3f).coerceAtMost(1f)   // the place slides to the centre first
            glCamera.lat = la; glCamera.lon = lo; glCamera.zoom = z
            glCamera.centreX = cx; glCamera.centreY = 0.5f
            glCamera.dive = d
            glCamera.focusRegion = focusRegion
            glCamera.sunLat = sun.first; glCamera.sunLon = sun.second
            glCamera.stills = stills
            overlay.setCamera(la, lo, z, Offset(size.width * cx, size.height * 0.5f), size.height)
            overlay.draw(this, focus, nav.regions(), nav.places(), pulse, locale)
        }
        Panel(catalog, focus, now, Modifier.align(Alignment.CenterStart).padding(start = 56.dp)
            .graphicsLayer { alpha = chromeAlpha() })
        Hints(focus, Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 34.dp)
            .graphicsLayer { alpha = chromeAlpha() })
        UpdateNote(Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 48.dp)
            .graphicsLayer { alpha = chromeAlpha() })
    }
}

/** The globe sits right of centre: the left third holds the panel. */
private const val CENTRE_X = 0.62f

/** How much closer the camera is at the bottom of the dive than at the place's normal view. */
private const val DIVE_ZOOM = 30f
private const val DIVE_MS = 1_100
private const val SURFACE_MS = 1_150

@Composable
private fun Panel(catalog: Catalog, focus: GlobeFocus, now: Long, modifier: Modifier) {
    val locale = LocalAppLocale.current
    AnimatedContent(
        targetState = focus,
        transitionSpec = { fadeIn(tween(380, delayMillis = 120)) togetherWith fadeOut(tween(160)) },
        label = "panel",
        modifier = modifier.width(300.dp),
    ) { f ->
        val eyebrow: String
        val title: String
        val cams: Int
        val live: Int
        val tz: String
        when (f) {
            is GlobeFocus.AtRegion -> {
                eyebrow = stringResource(R.string.globe_world); title = f.region.name(locale)
                cams = f.region.cams; live = f.region.live; tz = f.region.tz
            }
            is GlobeFocus.AtPlace -> {
                eyebrow = catalog.region(f.place.region)?.name(locale).orEmpty(); title = f.place.name
                cams = f.place.cams; live = f.place.live; tz = f.place.tz
            }
        }
        val daylight = Sphere.isDaylight(f.lat, f.lon, remember(now / 600_000) { Sphere.subsolar() })
        Column {
            BasicText(eyebrow.uppercase(locale), style = Type.label(12.sp, Palette.Light))
            Spacer(Modifier.height(6.dp))
            BasicText(title, style = Type.place(44.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(formatLocalTime(now, tz, localTimeFormatter()), style = Type.numeric(22.sp, weight = 300))
                Spacer(Modifier.width(10.dp))
                BasicText(
                    stringResource(if (daylight) R.string.globe_day else R.string.globe_night),
                    style = Type.label(12.sp, Palette.FgFaint),
                )
            }
            Spacer(Modifier.height(4.dp))
            BasicText(
                when {
                    !LocalGraph.current.settings.settings.collectAsState().value.stills ->
                        pluralStringResource(R.plurals.globe_live_only, live, live)
                    live > 0 -> stringResource(R.string.globe_cams_live, cams, live)
                    else -> stringResource(R.string.globe_cams, cams)
                },
                style = Type.label(13.sp, Palette.FgMuted, weight = 400),
            )
        }
    }
}

@Composable
private fun Hints(focus: GlobeFocus, modifier: Modifier) {
    val dive = stringResource(if (focus is GlobeFocus.AtRegion) R.string.hint_explore else R.string.hint_watch)
    BasicText(
        "${stringResource(R.string.hint_travel)}   ·   OK  $dive   ·   ⏯  ${stringResource(R.string.hint_surprise)}" +
            if (focus is GlobeFocus.AtRegion) "   ·   " + stringResource(R.string.hint_settings) else "",
        style = Type.label(12.sp, Palette.FgFaint, weight = 400),
        modifier = modifier,
    )
}
