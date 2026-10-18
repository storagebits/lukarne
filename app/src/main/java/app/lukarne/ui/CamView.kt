package app.lukarne.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.foundation.Image
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import app.lukarne.globe.Sphere
import app.lukarne.player.FrameStore
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import app.lukarne.ui.theme.Type
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import app.lukarne.LocalGraph
import app.lukarne.model.StreamKind
import app.lukarne.model.Webcam
import app.lukarne.player.HlsPool
import app.lukarne.player.YouTubeStage
import app.lukarne.ui.theme.Palette

/**
 * A webcam, filling its box. The still ([Webcam.thumb], or the refreshed image) is always drawn first, and the
 * live video fades in over it once its first frame is rendered — never a black box, never a spinner.
 *
 * @param live ask for live video (HLS player or the YouTube stage). False = still only.
 * @param fullscreen lifts the resolution cap and makes the cam audible.
 * @param audible the cam's sound; follows [fullscreen] unless set (the corner player keeps its sound).
 * @param onFailed called once if the live view fails or times out.
 * @param onPlaying called when the live video is on screen (the demo waits for it before revealing a cam).
 */
@Composable
fun CamView(
    cam: Webcam,
    live: Boolean,
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
    audible: Boolean = fullscreen,
    onFailed: () -> Unit = {},
    onPlaying: () -> Unit = {},
) {
    val graph = LocalGraph.current
    Box(modifier.background(Palette.Ink)) {
        Still(cam, fullscreen, onUnavailable = {
            // For a still cam the image *is* the cam; for a live cam a missing thumbnail is cosmetic.
            if (cam.kind == StreamKind.IMAGE) { graph.health.markDead(cam.id, "image"); onFailed() }
        })
        if (live) when (cam.kind) {
            StreamKind.HLS -> HlsLayer(cam, fullscreen, audible, onFailed, onPlaying)
            StreamKind.YOUTUBE -> YouTubeLayer(cam, fullscreen, audible, onFailed, onPlaying)
            StreamKind.IMAGE -> Unit
        }
    }
}

@Composable
private fun Still(cam: Webcam, fullscreen: Boolean, onUnavailable: () -> Unit) {
    val store = LocalGraph.current.frames
    val frames by store.frames.collectAsState()
    val at = frames[cam.id]?.takeIf { cam.kind == StreamKind.HLS && System.currentTimeMillis() - it < frameMaxAgeMs(cam) }
    if (at != null) {
        // A frame this device decoded itself beats any source still: sharp, and recent.
        FrameStill(store.file(cam.id), at) { SourceStill(cam, fullscreen, onUnavailable) }
    } else {
        SourceStill(cam, fullscreen, onUnavailable)
    }
}

/** How long a grabbed frame stands for the cam: briefly where the source has its own refreshed still. */
private fun frameMaxAgeMs(cam: Webcam): Long =
    if (cam.kind == StreamKind.HLS && cam.thumb != null) 20 * 60_000L else 3 * 3_600_000L

@Composable
private fun SourceStill(cam: Webcam, fullscreen: Boolean, onUnavailable: () -> Unit) {
    val url = cam.thumb ?: (if (cam.kind == StreamKind.IMAGE) cam.stream else null)
    if (url == null) {
        // A live-only source with no still (DelDOT) waiting for a player: a quiet card, not a black hole.
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.Deep, Palette.Ink))),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("▶", style = Type.label(22.sp, Palette.FgFaint))
        }
        return
    }
    if (cam.kind == StreamKind.YOUTUBE) {
        // YouTube's live thumbnail is a poster chosen by the channel (often daylight, often months old): shown
        // sharp but veiled: YouTube's terms forbid keeping frames of its streams (no FrameStore). Where it is night,
        // the veil is heavy, so a sunny poster doesn't pass for the scene at 3 a.m.
        val night = remember(cam.id) { !Sphere.isDaylight(cam.lat, cam.lon, Sphere.subsolar()) }
        Box(Modifier.fillMaxSize()) {
            RefreshingStill(url, 30 * 60_000L, Modifier.fillMaxSize(), maxPx = if (fullscreen) 960 else 480,
                onUnavailable = onUnavailable)
            Box(Modifier.fillMaxSize().background(Palette.Ink.copy(alpha = if (night) 0.62f else 0.3f)))
        }
        return
    }
    RefreshingStill(url, stillPeriodMs(cam, fullscreen), Modifier.fillMaxSize(), onUnavailable = onUnavailable)
}

/** A grabbed frame from disk; [placeholder] shows until it has decoded, and the previous frame until a new one has. */
@Composable
private fun FrameStill(file: File, at: Long, placeholder: @Composable () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file, at) {
        val request = ImageRequest.Builder(context)
            .data(file)
            .memoryCacheKey("frame:${file.name}@$at")
            .diskCachePolicy(CachePolicy.DISABLED)
            .size(FrameStore.WIDTH, FrameStore.WIDTH)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        if (result is SuccessResult) bitmap = result.image.toBitmap().asImageBitmap()
    }
    val b = bitmap
    if (b == null) placeholder() else Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}

/** How often a still is re-fetched. Sources update every ~1 min (Caltrans, DelDOT), ~10 min (Digitraffic). */
private fun stillPeriodMs(cam: Webcam, fullscreen: Boolean): Long = when (cam.kind) {
    StreamKind.IMAGE -> if (fullscreen) 20_000 else 30_000
    StreamKind.HLS -> 30_000
    StreamKind.YOUTUBE -> 30 * 60_000
}

@OptIn(UnstableApi::class)
@Composable
private fun HlsLayer(cam: Webcam, fullscreen: Boolean, audible: Boolean, onFailed: () -> Unit, onPlaying: () -> Unit) {
    val pool = LocalGraph.current.hls
    var handle by remember { mutableStateOf<HlsPool.Handle?>(null) }
    DisposableEffect(cam.id, fullscreen, audible) {
        val h = pool.acquire(cam, lowRes = !fullscreen)
        handle = h
        if (h != null) pool.setAudible(h, audible)
        onDispose {
            handle = null
            if (h != null) { pool.setAudible(h, false); pool.release(h) }
        }
    }
    val h = handle ?: return
    val state by h.state.collectAsState()
    LaunchedEffect(state) {
        if (state == HlsPool.State.FAILED) onFailed()
        if (state == HlsPool.State.PLAYING) onPlaying()
    }
    val frames = LocalGraph.current.frames
    var texture by remember { mutableStateOf<TextureView?>(null) }
    LaunchedEffect(state, texture) {
        val tv = texture ?: return@LaunchedEffect
        if (state != HlsPool.State.PLAYING) return@LaunchedEffect
        delay(GRAB_FIRST_MS)
        while (true) {
            // The texture holds the whole video frame (the zoom crop is the view's), without any overlay.
            if (tv.isAvailable && tv.width > 0 && frames.recent(cam.id, GRAB_EVERY_MS / 2) == null) {
                tv.getBitmap(FrameStore.WIDTH, FrameStore.WIDTH * tv.height / tv.width)?.let { frames.put(cam.id, it) }
            }
            delay(GRAB_EVERY_MS)
        }
    }
    val alpha = animateFloatAsState(if (state == HlsPool.State.PLAYING) 1f else 0f, tween(350), label = "hls")
    AndroidView(
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
        factory = { ctx ->
            AspectRatioFrameLayout(ctx).apply {
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                addView(TextureView(ctx).also { texture = it })
            }
        },
        update = { frame ->
            val player = h.player
            if ((frame.tag as? Binding)?.player !== player) {
                frame.unbind()
                val tv = frame.getChildAt(0) as TextureView
                val listener = object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) = frame.fit(videoSize)
                }
                player.setVideoTextureView(tv)
                player.addListener(listener)
                frame.tag = Binding(player, listener)
                frame.fit(player.videoSize)
            }
        },
        onRelease = { it.unbind(); texture = null },
    )
}

/** First grab once the picture has settled; then a fresh one now and then while the cam keeps playing. */
private const val GRAB_FIRST_MS = 4_000L
private const val GRAB_EVERY_MS = 3 * 60_000L

private class Binding(val player: Player, val listener: Player.Listener)

private fun AspectRatioFrameLayout.unbind() {
    val b = tag as? Binding ?: return
    b.player.removeListener(b.listener)
    b.player.clearVideoTextureView(getChildAt(0) as TextureView)
    tag = null
}

@OptIn(UnstableApi::class)
private fun AspectRatioFrameLayout.fit(v: VideoSize) {
    if (v.width > 0 && v.height > 0) setAspectRatio(v.width * v.pixelWidthHeightRatio / v.height)
}

@Composable
private fun YouTubeLayer(cam: Webcam, fullscreen: Boolean, audible: Boolean, onFailed: () -> Unit, onPlaying: () -> Unit) {
    // YouTube's terms forbid anything drawn over its player (a wall tile's caption and focus ring) and playback
    // in a player not yet visible: YouTube plays in fullscreen and the corner player only, shown from the start.
    // A wall's YouTube tile shows its poster.
    if (!fullscreen && !LocalInMini.current) return
    val stage = LocalGraph.current.youtube
    val me = remember { Any() }
    DisposableEffect(cam.id, fullscreen, audible) {
        stage.claim(me, cam.stream, audible = audible)
        onDispose { stage.release(me) }
    }
    val owner by stage.owner.collectAsState()
    val state by stage.state.collectAsState()
    val playingId by stage.playingId.collectAsState()
    val mine = owner === me
    val playing = mine && state == YouTubeStage.State.PLAYING && playingId == cam.stream
    LaunchedEffect(state, mine) { if (mine && state == YouTubeStage.State.FAILED) onFailed() }
    LaunchedEffect(playing) { if (playing) onPlaying() }
    // Each host is a plain frame; the single WebView moves into whichever host owns the stage.
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> FrameLayout(ctx) },
        update = { host ->
            if (mine && stage.view.parent !== host) { stage.detach(); host.addView(stage.view) }
        },
        onRelease = { host -> if (stage.view.parent === host) host.removeView(stage.view) },
    )
}
