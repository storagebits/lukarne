package app.lukarne.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import app.lukarne.LocalGraph
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.collectAsState
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import app.lukarne.model.StreamKind
import app.lukarne.model.Webcam
import app.lukarne.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * One cam, edge to edge. LEFT/RIGHT walk the zone's cams without going back to the mosaic; the HLS neighbours
 * are already buffering (muted, no surface) so the switch is a texture swap. Dead cams are skipped.
 */
@Composable
fun FullscreenScreen(
    cams: List<Webcam>,
    startIndex: Int,
    onSurprise: () -> Unit,
    onSettings: () -> Unit,
    onBack: (Webcam) -> Unit,
) {
    val graph = LocalGraph.current
    var index by remember { mutableIntStateOf(startIndex.coerceIn(cams.indices)) }
    var bandVisible by remember { mutableStateOf(true) }
    var bandNonce by remember { mutableIntStateOf(0) }
    val cam = cams[index]

    fun step(dir: Int) {
        // Skip cams known dead on this device; if every cam is dead, stay put.
        var i = index
        repeat(cams.size) {
            i = (i + dir).mod(cams.size)
            if (!graph.health.isDead(cams[i].id)) {
                index = i; bandNonce++; return
            }
        }
    }

    LaunchedEffect(bandNonce) {
        bandVisible = true
        delay(BAND_MS)
        bandVisible = false
    }

    Prefetch(cams, index)

    val focus = remember { FocusRequester() }
    TakeRemote(focus)
    Box(
        Modifier.fillMaxSize().background(Palette.Ink)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { step(+1); true }
                    Key.DirectionLeft -> { step(-1); true }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter -> { bandNonce++; true }
                    Key.Back, Key.Escape -> { onBack(cam); true }
                    Key.MediaPlayPause, Key.MediaPlay -> { onSurprise(); true }
                    Key.Menu -> { onSettings(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // Nothing may be drawn over a YouTube player (its terms): the picture makes room for the band instead, and
        // the two never overlap, even mid-animation (the picture shrinks before the band fades in, and grows after
        // it has faded out).
        val youTube = cam.kind == StreamKind.YOUTUBE
        var bandPx by remember { mutableIntStateOf(0) }
        val room by animateDpAsState(
            if (youTube && bandVisible) with(LocalDensity.current) { bandPx.toDp() } else 0.dp,
            tween(SQUEEZE_MS, delayMillis = if (bandVisible) 0 else SQUEEZE_MS),
            label = "room",
        )
        key(cam.id) {
            if (cam.kind == StreamKind.IMAGE) Postcard(cam, onFailed = { step(+1) })
            else CamView(cam, live = true, fullscreen = true, modifier = Modifier.fillMaxSize().padding(bottom = room),
                onFailed = { step(+1) })
        }
        AnimatedVisibility(
            visible = bandVisible,
            enter = fadeIn(tween(400, delayMillis = if (youTube) SQUEEZE_MS else 0)),
            exit = fadeOut(tween(if (youTube) SQUEEZE_MS else 900)),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            InfoBand(cam, position = index + 1, total = cams.size, overVideo = !youTube,
                modifier = Modifier.onSizeChanged { bandPx = it.height })
        }
    }
}

/**
 * Makes LEFT/RIGHT instant: the previous and next HLS cams keep buffering (muted, no surface) while one is on
 * screen, and the neighbours' stills are pulled into the image cache.
 */
@Composable
private fun Prefetch(cams: List<Webcam>, index: Int) {
    if (cams.size < 2) return
    val pool = LocalGraph.current.hls
    val context = LocalContext.current
    val around = listOf(cams[(index + 1).mod(cams.size)], cams[(index - 1).mod(cams.size)]).distinctBy { it.id }
    val hls = around.filter { it.kind == StreamKind.HLS }
    DisposableEffect(hls.joinToString { it.id }) {
        val handles = hls.mapNotNull { pool.acquire(it, lowRes = false) }
        onDispose { handles.forEach(pool::release) }
    }
    LaunchedEffect(around.joinToString { it.id }) {
        val loader = SingletonImageLoader.get(context)
        around.mapNotNull { it.thumb ?: it.stream.takeIf { _ -> it.kind == StreamKind.IMAGE } }.forEach { url ->
            loader.enqueue(ImageRequest.Builder(context).data(url).size(960, 960).allowHardware(false).build())
        }
    }
}

private const val BAND_MS = 4_500L
private const val SQUEEZE_MS = 450
