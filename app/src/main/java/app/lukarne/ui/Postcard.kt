package app.lukarne.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.lukarne.LocalGraph
import app.lukarne.model.Webcam
import app.lukarne.ui.theme.Palette

/**
 * Fullscreen for a still cam. Stills are often small, so stretching one edge to edge reads as broken
 * video. Instead: the same image, tiny and stretched, as a soft blurred ground (API 30 has no RenderEffect), and
 * the sharp frame on top like a postcard. It refreshes on its own.
 */
@Composable
fun Postcard(cam: Webcam, onFailed: () -> Unit) {
    val graph = LocalGraph.current
    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        RefreshingStill(cam.stream, 60_000, Modifier.fillMaxSize(), ContentScale.Crop, maxPx = 24)
        Box(Modifier.fillMaxSize().background(Palette.Ink.copy(alpha = 0.45f)))
        val shape = RoundedCornerShape(6.dp)
        Box(
            Modifier.align(Alignment.Center).padding(top = 40.dp, bottom = 110.dp)
                .fillMaxHeight().aspectRatio(320f / 224f)
                .shadow(30.dp, shape).clip(shape)
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
        ) {
            RefreshingStill(
                cam.stream, 20_000, Modifier.fillMaxSize(), ContentScale.Crop,
                onUnavailable = { graph.health.markDead(cam.id, "image"); onFailed() },
            )
        }
    }
}
