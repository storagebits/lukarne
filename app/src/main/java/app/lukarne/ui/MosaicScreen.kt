package app.lukarne.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.lukarne.LocalGraph
import androidx.compose.runtime.collectAsState
import app.lukarne.data.Place
import app.lukarne.data.Region
import app.lukarne.model.StreamKind
import app.lukarne.model.Webcam
import app.lukarne.ui.theme.Palette
import app.lukarne.ui.theme.Type
import kotlinx.coroutines.delay

private const val COLS = 3
private const val ROWS = 3
private const val PAGE = COLS * ROWS
// 960×540 dp canvas: header + 3 rows fit inside a 32 dp bottom overscan margin.
private val TILE_W = 232.dp
private val TILE_H = 130.5.dp
private val GAP = 13.dp

/** Focus must rest this long on a tile before it starts a player: zapping across tiles costs nothing. */
private const val SETTLE_MS = 350L

/**
 * A 3×3 wall of the cams around [place]. The focused tile always goes live; a few other HLS tiles go live at low
 * resolution within the decoder budget; everything else is a still that refreshes itself. OK = fullscreen.
 *
 * @param zone the cams to show, best first (see Catalog.zone). Dead cams are swapped for the next in line.
 */
@Composable
fun MosaicScreen(
    place: Place,
    region: Region?,
    zone: List<Webcam>,
    initialFocus: Int,
    onOpen: (cams: List<Webcam>, index: Int) -> Unit,
    onSurprise: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val graph = LocalGraph.current
    // The cams currently on the wall; a dead cam is replaced in place by the next unused one from the zone.
    val wall = remember(zone) { mutableStateListOf<Webcam>().apply { addAll(zone) } }
    var focus by remember(zone) { mutableIntStateOf(initialFocus.coerceIn(0, (zone.size - 1).coerceAtLeast(0))) }
    var settled by remember(zone) { mutableIntStateOf(-1) }
    LaunchedEffect(focus) { delay(SETTLE_MS); settled = focus }
    // The corner player sits over the bottom-right tile: it moves to the other corner while that tile has focus.
    val miniLeft = LocalMiniLeft.current
    val underMini = focus % COLS == COLS - 1 && focus % PAGE / COLS == ROWS - 1
    LaunchedEffect(underMini) { miniLeft?.value = underMini }
    DisposableEffect(Unit) { onDispose { miniLeft?.value = false } }

    if (wall.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Palette.Ink))
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val page = focus / PAGE
    val pageCams = wall.drop(page * PAGE).take(PAGE)

    fun replaceDead(cam: Webcam) {
        val i = wall.indexOf(cam)
        if (i < 0) return
        wall.removeAt(i)
        if (wall.isEmpty()) onBack() else if (focus >= wall.size) focus = wall.size - 1
    }

    // Live budget. A fixed set of HLS tiles per page stays live whatever the focus does (moving the focus must
    // not create and release players: ExoPlayer.release() blocks the main thread). One more slot follows the
    // focused tile when it is not in that set, and one is left for prefetching the fullscreen neighbour.
    val budget = (graph.hls.maxPlayers - 2).coerceAtLeast(0)
    val pageIds = pageCams.map { it.id }
    val steadyLive = remember(pageIds, budget) {
        // Cams with no still (DelDOT) have nothing else to show: they get the live slots first.
        pageCams.filter { it.kind == StreamKind.HLS }.sortedBy { it.thumb != null }.take(budget).map { it.id }.toSet()
    }
    val focusedCam = wall.getOrNull(settled)
    val liveIds = when {
        focusedCam == null || focusedCam.id !in pageIds -> steadyLive
        focusedCam.kind == StreamKind.IMAGE -> steadyLive
        else -> steadyLive + focusedCam.id   // HLS outside the set, or YouTube (one WebView: focused only)
    }

    val requester = remember { FocusRequester() }
    TakeRemote(requester)
    Box(
        Modifier.fillMaxSize().background(Palette.Ink)
            .focusRequester(requester)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val col = focus % COLS
                when (e.key) {
                    Key.DirectionRight -> { if (col < COLS - 1 && focus + 1 < wall.size) focus++; true }
                    Key.DirectionLeft -> { if (col > 0) focus--; true }
                    Key.DirectionDown -> {
                        // Rows continue onto the next page; a short last row catches the focus at its end.
                        if (focus + COLS < wall.size) focus += COLS
                        else if (focus / COLS < (wall.size - 1) / COLS) focus = wall.size - 1
                        true
                    }
                    Key.DirectionUp -> { if (focus - COLS >= 0) focus -= COLS; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onOpen(wall.toList(), focus); true }
                    Key.Back, Key.Escape -> { onBack(); true }
                    Key.MediaPlayPause, Key.MediaPlay -> { onSurprise(); true }
                    Key.Menu -> { onSettings(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Column(Modifier.fillMaxSize().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Header(place, region, wall.size, page, tz = zone.first().tz)
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInVertically(tween(380)) { it / 3 * dir } + fadeIn(tween(380))) togetherWith
                        (slideOutVertically(tween(300)) { -it / 3 * dir } + fadeOut(tween(250)))
                },
                label = "page",
            ) { p ->
                val cams = wall.drop(p * PAGE).take(PAGE)
                Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                    for (r in 0 until ROWS) {
                        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                            for (c in 0 until COLS) {
                                val i = r * COLS + c
                                val cam = cams.getOrNull(i)
                                if (cam == null) {
                                    Spacer(Modifier.size(TILE_W, TILE_H))
                                } else {
                                    val onFailed = remember(cam.id) { { replaceDead(cam) } }
                                    Tile(
                                        cam = cam,
                                        focused = p * PAGE + i == focus,
                                        live = cam.id in liveIds && p == page,
                                        onFailed = onFailed,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // Credits for what is on this page (Digitraffic's CC BY requires it wherever its images show).
        val credits = pageCams.map { it.source }.distinct().sorted()
        BasicText(
            credits.map { sourceCredit(it) }.joinToString("  ·  "),
            style = Type.label(10.sp, Palette.FgFaint, weight = 400),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp),
        )
    }
}

@Composable
private fun Header(place: Place, region: Region?, count: Int, page: Int, tz: String) {
    val locale = LocalAppLocale.current
    val now = rememberMinuteClock()
    Row(Modifier.width(TILE_W * COLS + GAP * (COLS - 1)), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            BasicText(
                (region?.name(locale) ?: "").uppercase(locale),
                style = Type.label(12.sp, Palette.Light),
            )
            BasicText(place.name, style = Type.place(30.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val pages = (count + PAGE - 1) / PAGE
        if (pages > 1) {
            BasicText("${page + 1} / $pages", style = Type.label(13.sp, Palette.FgFaint), modifier = Modifier.padding(end = 20.dp, bottom = 8.dp))
        }
        BasicText(formatLocalTime(now, tz, localTimeFormatter()), style = Type.numeric(26.sp, weight = 300))
    }
}

@Composable
private fun Tile(cam: Webcam, focused: Boolean, live: Boolean, onFailed: () -> Unit) {
    // Animated values are read in draw/layer lambdas only: the tile is not recomposed on every animation frame.
    val scale = animateFloatAsState(if (focused) 1.09f else 1f, tween(220), label = "scale")
    val dim = animateFloatAsState(if (focused) 0f else 0.28f, tween(220), label = "dim")
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(TILE_W, TILE_H)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale.value; scaleY = scale.value
                shadowElevation = if (focused) 18.dp.toPx() else 0f
                this.shape = shape
                clip = true
                ambientShadowColor = Palette.Light
                spotShadowColor = Palette.Light
            }
            .then(if (focused) Modifier.border(2.dp, Palette.LightHot, shape) else Modifier)
            .drawWithContent {
                drawContent()
                if (dim.value > 0f) drawRect(Palette.Ink.copy(alpha = dim.value))
            },
    ) {
        CamView(cam, live = live, fullscreen = false, modifier = Modifier.fillMaxSize(), onFailed = onFailed)
        Caption(cam, Modifier.align(Alignment.BottomStart))
        // A YouTube tile shows the stream's YouTube thumbnail, which must say where it comes from (YouTube's terms).
        if (cam.kind == StreamKind.YOUTUBE) {
            BasicText(
                "YouTube", style = Type.label(10.sp, Palette.Fg, weight = 500),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Palette.Ink.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Place + local time, on a gradient so it reads over snow and sky alike. */
@Composable
private fun Caption(cam: Webcam, modifier: Modifier) {
    val now = rememberMinuteClock()
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Ink.copy(alpha = 0.82f))))
            .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cam.isLive) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Light))
            Spacer(Modifier.width(6.dp))
        }
        BasicText(
            cam.place, style = Type.place(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        BasicText(formatLocalTime(now, cam.tz, localTimeFormatter()), style = Type.numeric(12.sp, Palette.FgMuted))
    }
}
