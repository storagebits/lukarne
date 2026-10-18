package app.lukarne.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.lukarne.LocalGraph
import app.lukarne.R
import app.lukarne.data.Catalog
import app.lukarne.model.StreamKind
import app.lukarne.model.Webcam
import app.lukarne.player.HlsPool
import app.lukarne.ui.theme.Palette
import app.lukarne.ui.theme.Type
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Demo: random live cams around the world, [seconds] each, until a key stops it.
 *
 * Between two cams the picture dips to a title card (region, place, local time) instead of cutting. The next HLS
 * cam starts playing hidden under the card and is only revealed once its video is on screen; while it is up, it
 * settles from a slight zoom (Ken Burns). Two slots alternate so the revealed cam's player is never remounted.
 *
 * YouTube's terms set other rules for its player: nothing drawn over it, no playback before it is visible, no
 * change to it. So a YouTube cam starts only once its card has gone, in plain sight, without zoom or caption, and
 * the card of the cam after it waits until it has faded out.
 *
 * RIGHT (or play/pause) skips, OK stays on the cam (normal fullscreen), BACK stops on the globe at that place.
 */
@Composable
fun DemoScreen(
    catalog: Catalog,
    seconds: Int,
    pick: (exclude: Set<String>, notRegion: String?) -> Webcam?,
    onStay: (Webcam) -> Unit,
    onStop: (Webcam?) -> Unit,
) {
    val graph = LocalGraph.current
    val slots = remember { List(2) { Slot() } }
    var front by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf<Webcam?>(null) }
    val titleAlpha = remember { Animatable(0f) }
    var bandNonce by remember { mutableIntStateOf(0) }
    var bandVisible by remember { mutableStateOf(false) }
    val skips = remember { Channel<Unit>(Channel.CONFLATED) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val recent = ArrayDeque<String>()
        fun next(after: Webcam?): Webcam? =
            pick(recent.toSet(), after?.let { catalog.place(it.cluster)?.region })?.also {
                recent.addLast(it.id); if (recent.size > RECENT) recent.removeFirst()
            }

        var upcoming = next(null) ?: return@LaunchedEffect onStop(null)
        while (true) {
            val out = slots[front]
            val inc = slots[1 - front]
            // 1. Dip: the cam on screen drifts forward and fades while the next place's name comes up.
            val outYouTube = out.cam?.kind == StreamKind.YOUTUBE
            title = upcoming
            bandVisible = false
            coroutineScope {
                launch { out.alpha.animateTo(0f, tween(DIP_MS, easing = EASE)) }
                if (!outYouTube) launch { out.scale.animateTo(out.scale.value + 0.03f, tween(DIP_MS, easing = EASE)) }
                // Over a YouTube player, the card would cover it: it waits for the player to be gone.
                launch {
                    delay(if (outYouTube) DIP_MS.toLong() else DIP_MS / 4L)
                    titleAlpha.animateTo(1f, tween(TITLE_IN_MS, easing = EASE))
                }
            }
            out.cam = null   // frees its player (YouTube has one: the next cam may need it)
            // 2. The next cam starts (HLS hidden under the card, YouTube after it); a dead or slow one is swapped.
            var shownAt = System.currentTimeMillis()
            var cam: Webcam = upcoming
            while (true) {
                val youTube = cam.kind == StreamKind.YOUTUBE
                if (youTube) {
                    delay((TITLE_MIN_MS - (System.currentTimeMillis() - shownAt)).coerceAtLeast(0))
                    titleAlpha.animateTo(0f, tween(TITLE_OUT_MS, easing = EASE))
                }
                inc.reset(cam, shown = youTube)
                val ok = withTimeoutOrNull(START_TIMEOUT_MS) {
                    snapshotFlow { inc.playing || inc.failed }.first { it }
                    inc.playing
                } ?: false
                if (ok) break
                inc.cam = null
                cam = next(cam) ?: return@LaunchedEffect onStop(null)
                title = cam
                if (titleAlpha.value < 1f) {
                    titleAlpha.animateTo(1f, tween(TITLE_IN_MS, easing = EASE))
                    shownAt = System.currentTimeMillis()
                }
            }
            delay((TITLE_MIN_MS - (System.currentTimeMillis() - shownAt)).coerceAtLeast(0))
            // 3. Reveal, then a slow settle over the whole stay.
            front = 1 - front
            val stayMs = seconds * 1000L
            coroutineScope {
                launch { inc.alpha.animateTo(1f, tween(REVEAL_MS, easing = EASE)) }
                launch { titleAlpha.animateTo(0f, tween(TITLE_OUT_MS, delayMillis = REVEAL_MS / 3, easing = EASE)) }
            }
            scope.launch { inc.scale.animateTo(1f, tween((stayMs + DIP_MS).toInt(), easing = LinearEasing)) }
            bandVisible = true; bandNonce++
            upcoming = next(cam) ?: return@LaunchedEffect onStop(cam)
            // 4. Stay. An HLS successor starts buffering a few seconds early, so its title is short.
            val next = upcoming
            val warm = if (next.kind == StreamKind.HLS) {
                scope.launch { delay((stayMs - PREFETCH_MS).coerceAtLeast(0)); prefetch(graph.hls, next) }
            } else null
            withTimeoutOrNull(stayMs) { skips.receive() }
            warm?.cancel()   // the pool lingers the player past the dip, until the slot takes it
        }
    }

    LaunchedEffect(bandNonce) {
        if (bandNonce == 0) return@LaunchedEffect
        delay(BAND_MS)
        bandVisible = false
    }

    val focus = remember { FocusRequester() }
    TakeRemote(focus)
    val onScreen = slots[front].cam
    Box(
        Modifier.fillMaxSize().background(Palette.Ink)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight, Key.MediaPlayPause, Key.MediaPlay, Key.MediaNext -> { skips.trySend(Unit); true }
                    Key.DirectionCenter, Key.Enter -> { (onScreen ?: title)?.let(onStay) ?: onStop(null); true }
                    Key.Back, Key.Escape -> { onStop(onScreen ?: title); true }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft -> { bandVisible = true; bandNonce++; true }
                    else -> true
                }
            }
            .focusable(),
    ) {
        // A faint vignette behind the title card, so the dip is a colour, not a void.
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Palette.Deep, Palette.Ink))))
        // Fixed order (a reorder would remount the players); the one on screen is drawn over the incoming one.
        slots.forEachIndexed { i, slot ->
            val cam = slot.cam ?: return@forEachIndexed
            key(i, slot.epoch) {
                Box(Modifier.fillMaxSize().zIndex(if (i == front) 1f else 0f).graphicsLayer {
                    alpha = slot.alpha.value
                    scaleX = slot.scale.value; scaleY = slot.scale.value
                }) {
                    CamView(
                        cam, live = true, fullscreen = true, modifier = Modifier.fillMaxSize(),
                        onFailed = { slot.failed = true },
                        onPlaying = { slot.playing = true },
                    )
                }
            }
        }
        title?.let { TitleCard(catalog, it, Modifier.align(Alignment.Center).zIndex(2f).graphicsLayer {
            alpha = titleAlpha.value
            translationY = (1f - titleAlpha.value) * 14.dp.toPx()
        }) }
        AnimatedVisibility(
            // Never over a YouTube player (its terms): a YouTube cam's place was on its card.
            visible = bandVisible && onScreen != null && onScreen.kind != StreamKind.YOUTUBE && titleAlpha.value < 0.05f,
            enter = fadeIn(tween(600)), exit = fadeOut(tween(900)),
            modifier = Modifier.align(Alignment.BottomStart).zIndex(2f),
        ) {
            onScreen?.let { InfoBand(it, position = null, total = 0) }
        }
        AnimatedVisibility(
            visible = bandVisible && onScreen?.kind != StreamKind.YOUTUBE,
            enter = fadeIn(tween(600)), exit = fadeOut(tween(900)),
            modifier = Modifier.align(Alignment.TopEnd).zIndex(2f).padding(top = 28.dp, end = 40.dp),
        ) {
            BasicText(stringResource(R.string.demo_hint), style = Type.label(11.sp, Palette.FgFaint, weight = 400))
        }
    }
}

/** Region, place and local time, centred: what the dip between two cams shows. */
@Composable
private fun TitleCard(catalog: Catalog, cam: Webcam, modifier: Modifier) {
    val locale = LocalAppLocale.current
    val now = rememberMinuteClock()
    val region = catalog.place(cam.cluster)?.region?.let(catalog::region)
    Column(modifier.padding(horizontal = 80.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        BasicText(
            listOfNotNull(region?.name(locale), countryName(cam.country, locale)).distinct().joinToString("  ·  ")
                .uppercase(locale),
            style = Type.label(13.sp, Palette.Light),
        )
        Spacer(Modifier.height(10.dp))
        BasicText(
            cam.place, style = Type.place(52.sp).copy(textAlign = TextAlign.Center),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.width(36.dp).height(1.dp).background(Palette.Light.copy(alpha = 0.6f)))
        Spacer(Modifier.height(12.dp))
        BasicText(formatLocalTime(now, cam.tz, localTimeFormatter()), style = Type.numeric(22.sp, Palette.FgMuted, weight = 300))
    }
}

/** One of the two layers the demo alternates between. [epoch] remounts the player for a new cam. */
private class Slot {
    var cam by mutableStateOf<Webcam?>(null)
    var epoch by mutableIntStateOf(0)
    var playing by mutableStateOf(false)
    var failed by mutableStateOf(false)
    val alpha = Animatable(0f)
    val scale = Animatable(1f)

    /** A [shown] slot is visible from the start (YouTube: no hidden playback, and no zoom on its player). */
    suspend fun reset(c: Webcam, shown: Boolean) {
        playing = false; failed = false
        alpha.snapTo(if (shown) 1f else 0f)
        scale.snapTo(if (c.kind == StreamKind.YOUTUBE) 1f else START_SCALE)
        epoch++
        cam = c
    }
}

/** Holds [cam]'s player until cancelled; HlsPool lingers it 1.5 s after, longer than the dip before its slot mounts. */
private suspend fun prefetch(pool: HlsPool, cam: Webcam) {
    val h = pool.acquire(cam, lowRes = false) ?: return
    try { awaitCancellation() } finally { pool.release(h) }
}

private val EASE = CubicBezierEasing(0.33f, 0f, 0.2f, 1f)
private const val DIP_MS = 1_100
private const val TITLE_IN_MS = 900
private const val TITLE_OUT_MS = 900
private const val REVEAL_MS = 1_600
/** The card stays at least this long, even when the next cam is already playing. */
private const val TITLE_MIN_MS = 2_200L
private const val START_TIMEOUT_MS = 12_000L
private const val PREFETCH_MS = 10_000L
private const val START_SCALE = 1.06f
private const val BAND_MS = 6_000L
/** Cams not shown again until this many others have been. */
private const val RECENT = 40
