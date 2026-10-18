package app.lukarne.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import app.lukarne.model.StreamKind
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.lukarne.LocalGraph
import app.lukarne.data.Catalog
import app.lukarne.globe.GlobeFocus
import app.lukarne.globe.GlobeGeometry
import app.lukarne.globe.Sphere
import app.lukarne.model.Webcam
import app.lukarne.ui.theme.Palette
import java.util.TimeZone

/** The three screens, as a tiny back stack: globe → mosaic → fullscreen. */
sealed interface Screen {
    /** [surfacing]: arriving from a place's wall, the camera climbs back out of the ground (see GlobeScreen). */
    data class Globe(val surfacing: Boolean = false) : Screen
    data class Mosaic(val placeId: String, val focus: Int = 0) : Screen
    data class Fullscreen(val cams: List<Webcam>, val index: Int, val from: Mosaic?) : Screen
    data object Demo : Screen
}

@Composable
fun LukarneRoot(catalog: Catalog, geometry: GlobeGeometry, start: Screen, startCam: Webcam? = null, onExit: () -> Unit) {
    val graph = LocalGraph.current
    var screen by remember { mutableStateOf(start) }
    var globeFocus by remember { mutableStateOf(initialFocus(catalog, start)) }
    var settingsOpen by remember { mutableStateOf(false) }
    var leaveOpen by remember { mutableStateOf(false) }
    val settings by graph.settings.settings.collectAsState()
    val stills = settings.stills
    // The cam left from fullscreen that keeps playing in a corner (settings.mini).
    var pinned by remember { mutableStateOf<Webcam?>(null) }
    val miniLeft = remember { mutableStateOf(false) }
    var focusEpoch by remember { mutableIntStateOf(0) }
    val openSettings = { settingsOpen = true }
    // The zone of a mosaic is computed once per visit, so returning from fullscreen finds the same wall.
    val zones = remember { mutableMapOf<String, List<Webcam>>() }
    fun zoneOf(placeId: String): List<Webcam> {
        val place = catalog.place(placeId) ?: return emptyList()
        return zones.getOrPut("$placeId|$stills") {
            catalog.zone(place, size = 9, withStills = stills) { graph.health.isDead(it.id) }
        }
    }

    fun openFullscreen(cam: Webcam, freshWall: Boolean) {
        if (freshWall) zones.remove("${cam.cluster}|$stills")
        val zone = zoneOf(cam.cluster).let { z -> if (cam in z) z else listOf(cam) + z }
        catalog.place(cam.cluster)?.let { globeFocus = GlobeFocus.AtPlace(it) }
        screen = Screen.Fullscreen(zone, zone.indexOf(cam), Screen.Mosaic(cam.cluster, zone.indexOf(cam)))
    }

    // Launch extra `cam`: straight to that cam in fullscreen, over its wall (captures, tools/make_screens.sh).
    LaunchedEffect(Unit) { startCam?.let { openFullscreen(it, freshWall = false) } }

    fun surprise() {
        val cam = pickSurprise(catalog, isDead = { graph.health.isDead(it.id) }) ?: return
        openFullscreen(cam, freshWall = true) // a fresh wall around the surprise
    }

    // Fullscreen and the demo take the screen (and the players): the corner player goes.
    LaunchedEffect(screen) { if (screen is Screen.Fullscreen || screen is Screen.Demo) pinned = null }

    CompositionLocalProvider(
        LocalFocusEpoch provides focusEpoch,
        LocalOverlayOpen provides (leaveOpen || settingsOpen),
        LocalMiniLeft provides miniLeft,
    ) {
    // Navigation state lives above this key; only the screens remount when the app returns from the background.
    key(LocalSession.current) {
    AnimatedContent(
        targetState = screen,
        contentKey = { it::class },
        transitionSpec = {
            val from = initialState; val to = targetState
            when {
                // The globe has dived into the place: the wall comes up towards the viewer out of that dive.
                from is Screen.Globe && to is Screen.Mosaic ->
                    (fadeIn(tween(420)) + scaleIn(tween(650, easing = LinearOutSlowInEasing), initialScale = 0.72f)) togetherWith
                        fadeOut(tween(380))
                // And back: the wall falls away while the globe climbs out of the ground (GlobeScreen.surfacing).
                from is Screen.Mosaic && to is Screen.Globe ->
                    fadeIn(tween(360, delayMillis = 60)) togetherWith
                        (fadeOut(tween(300)) + scaleOut(tween(420, easing = FastOutLinearInEasing), targetScale = 0.72f))
                // Into the demo and out of it: slow, like a screen dimming and lighting up again.
                to is Screen.Demo -> fadeIn(tween(900, delayMillis = 300)) togetherWith fadeOut(tween(700))
                from is Screen.Demo -> fadeIn(tween(700, delayMillis = 200)) togetherWith fadeOut(tween(600))
                else -> fadeIn(tween(320)) togetherWith fadeOut(tween(220))
            }
        },
        label = "screen",
        modifier = Modifier.fillMaxSize().background(Palette.Ink),
    ) { s ->
        when (s) {
            is Screen.Globe -> GlobeScreen(
                catalog = catalog,
                geometry = geometry,
                focus = globeFocus,
                onFocus = { globeFocus = it },
                onOpenPlace = { screen = Screen.Mosaic(it.id) },
                surfacing = s.surfacing,
                onSurfaced = { if (screen is Screen.Globe) screen = Screen.Globe() },   // not again on a resume
                onSurprise = ::surprise,
                onSettings = openSettings,
                // BACK on the whole globe closes the corner player first, then offers to leave.
                onExit = { if (pinned != null) pinned = null else leaveOpen = true },
            )
            is Screen.Mosaic -> {
                val place = catalog.place(s.placeId)
                if (place == null) { Box(Modifier.fillMaxSize()); screen = Screen.Globe(); return@AnimatedContent }
                MosaicScreen(
                    place = place,
                    region = catalog.region(place.region),
                    zone = zoneOf(place.id),
                    initialFocus = s.focus,
                    onOpen = { cams, i -> screen = Screen.Fullscreen(cams, i, s) },
                    onSurprise = ::surprise,
                    onSettings = openSettings,
                    onBack = {
                        globeFocus = GlobeFocus.AtPlace(place)
                        screen = Screen.Globe(surfacing = true)
                    },
                )
            }
            is Screen.Fullscreen -> FullscreenScreen(
                cams = s.cams,
                startIndex = s.index,
                onSurprise = ::surprise,
                onSettings = openSettings,
                onBack = { cam ->
                    if (settings.mini && cam.isLive) pinned = cam
                    val from = s.from
                    screen = from?.copy(focus = s.cams.indexOf(cam).coerceAtLeast(0)) ?: Screen.Globe()
                },
            )
            Screen.Demo -> DemoScreen(
                catalog = catalog,
                seconds = settings.demoSeconds,
                pick = { exclude, notRegion ->
                    pickSurprise(catalog, exclude, notRegion, isDead = { graph.health.isDead(it.id) })
                },
                onStay = { cam -> openFullscreen(cam, freshWall = true) },
                onStop = { cam ->
                    // Back on the globe, climbing out of the ground at the last place shown.
                    val place = cam?.let { catalog.place(it.cluster) }
                    if (place != null) globeFocus = GlobeFocus.AtPlace(place)
                    screen = Screen.Globe(surfacing = place != null)
                },
            )
        }
    }
        // The corner player, over the globe and the walls. It grows out of the fullscreen it replaces.
        val mini = pinned
        AnimatedVisibility(
            // A sheet's scrim would cover a YouTube player, which its terms forbid: the corner steps aside meanwhile.
            visible = mini != null && (screen is Screen.Globe || screen is Screen.Mosaic) &&
                !(mini.kind == StreamKind.YOUTUBE && (leaveOpen || settingsOpen)),
            enter = fadeIn(tween(450)) + scaleIn(tween(550, easing = LinearOutSlowInEasing), initialScale = 2.6f,
                transformOrigin = TransformOrigin(1f, 1f)),
            exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 0.85f, transformOrigin = TransformOrigin(1f, 1f)),
            modifier = Modifier.fillMaxSize(),
        ) {
            val left by miniLeft
            val bias by animateFloatAsState(if (left) -1f else 1f, tween(500), label = "miniSide")
            var last by remember { mutableStateOf(mini) }
            if (mini != null) last = mini
            Box(Modifier.fillMaxSize()) {
                last?.let { cam ->
                    MiniPlayer(
                        cam, onFailed = { if (pinned == cam) pinned = null },
                        modifier = Modifier.align(BiasAlignment(bias, 1f)).padding(horizontal = 40.dp, vertical = 30.dp),
                    )
                }
            }
        }
    }
    }
    AnimatedVisibility(leaveOpen, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        LeaveSheet(
            onDemo = { leaveOpen = false; focusEpoch++; screen = Screen.Demo },
            onSettings = { leaveOpen = false; settingsOpen = true },
            onQuit = onExit,
            onClose = { leaveOpen = false; focusEpoch++ },
        )
    }
    AnimatedVisibility(settingsOpen, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        SettingsSheet(onClose = { settingsOpen = false; focusEpoch++ })
    }
}

/**
 * "Surprise me": somewhere in daylight, anywhere in the world. A region is drawn first (weighted by the square root
 * of its daylight live cams, so California's 90 road cams don't win every time), then a live cam in it.
 */
private fun pickSurprise(
    catalog: Catalog,
    exclude: Set<String> = emptySet(),
    notRegion: String? = null,
    isDead: (Webcam) -> Boolean,
): Webcam? {
    val sun = Sphere.subsolar()
    // The demo asks for cams it has not shown lately, away from the region it is leaving (when it can).
    val alive = catalog.cams.filterNot { isDead(it) || it.id in exclude }
        .let { all -> all.filter { catalog.place(it.cluster)?.region != notRegion }.ifEmpty { all } }
    // Live only: a surprise should move. Stills are a fallback if nothing live answers.
    val pool = alive.filter { it.isLive }.ifEmpty { alive }
    val day = pool.filter { Sphere.isDaylight(it.lat, it.lon, sun) }.ifEmpty { pool }
    val byRegion = day.groupBy { catalog.place(it.cluster)?.region }.filterKeys { it != null }
    if (byRegion.isEmpty()) return day.randomOrNull()
    val weights = byRegion.mapValues { kotlin.math.sqrt(it.value.size.toDouble()) }
    var roll = kotlin.random.Random.nextDouble() * weights.values.sum()
    val region = weights.entries.first { roll -= it.value; roll <= 0 }.key
    val cams = byRegion.getValue(region)
    return cams.random()
}

/** Start where the viewer lives: the region whose cams share the device's time zone, else the liveliest. */
private fun initialFocus(catalog: Catalog, start: Screen): GlobeFocus {
    if (start is Screen.Mosaic) catalog.place(start.placeId)?.let { return GlobeFocus.AtPlace(it) }
    val tz = TimeZone.getDefault().id
    val home = catalog.regions.firstOrNull { it.tz == tz }
        ?: catalog.cams.firstOrNull { it.tz == tz }?.let { c -> catalog.place(c.cluster)?.region?.let(catalog::region) }
    // Start on a region with live video: home if it has some, else the live region nearest to home.
    val live = catalog.regions.filter { it.live > 0 }
    val start = when {
        home != null && home.live > 0 -> home
        home != null -> live.minByOrNull { Catalog.km(home.lat, home.lon, it.lat, it.lon) }
        else -> null
    } ?: live.maxByOrNull { it.live } ?: catalog.regions.first()
    return GlobeFocus.AtRegion(start)
}
