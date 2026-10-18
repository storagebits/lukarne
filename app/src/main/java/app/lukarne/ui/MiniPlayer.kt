package app.lukarne.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lukarne.model.Webcam
import app.lukarne.ui.theme.Palette
import app.lukarne.ui.theme.Type

/** Set by a wall when its focus is near the corner player: the player moves to the other corner. */
val LocalMiniLeft = staticCompositionLocalOf<MutableState<Boolean>?> { null }

/** True inside the corner player. */
val LocalInMini = staticCompositionLocalOf { false }

val MINI_W = 256.dp
/** 288 px on the Shield (320 dpi): above the 200 px YouTube's terms require of its player. */
val MINI_H = 144.dp

/**
 * The cam left from fullscreen, still playing (with its sound) in a corner while the viewer browses. Same player
 * as fullscreen (HLS shares the decoder by cam id, YouTube keeps its video id), so leaving fullscreen does not
 * restart the stream. The caption sits under the picture, never on it (YouTube's terms: nothing over its player).
 */
@Composable
fun MiniPlayer(cam: Webcam, onFailed: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.width(MINI_W)
            .graphicsLayer {
                shadowElevation = 24.dp.toPx()
                this.shape = shape
                clip = true
                ambientShadowColor = Color.Black
                spotShadowColor = Color.Black
            }
            .background(Palette.Ink)
            .border(1.dp, Palette.Light.copy(alpha = 0.45f), shape)
            .padding(1.dp),   // the border stays off the picture
    ) {
        Box(Modifier.fillMaxWidth().height(MINI_H)) {
            CompositionLocalProvider(LocalInMini provides true) {
                CamView(cam, live = true, fullscreen = false, audible = true, modifier = Modifier.fillMaxSize(), onFailed = onFailed)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 9.dp, end = 9.dp, top = 5.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(Palette.Light))
            Spacer(Modifier.width(6.dp))
            BasicText(cam.place, style = Type.place(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
