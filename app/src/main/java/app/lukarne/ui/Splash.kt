package app.lukarne.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lukarne.R
import app.lukarne.ui.theme.Palette
import app.lukarne.ui.theme.Type

/** While the catalogue and the globe load (~1–2 s): the wordmark and one light breathing. */
@Composable
fun Splash() {
    val breath by rememberInfiniteTransition(label = "splash").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "b",
    )
    Box(Modifier.fillMaxSize().background(Palette.Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Canvas(Modifier.size(56.dp)) {
                drawCircle(Brush.radialGradient(listOf(Palette.Light.copy(alpha = 0.55f * breath), Color.Transparent)))
                drawCircle(Palette.LightHot.copy(alpha = breath), radius = size.minDimension * 0.06f)
            }
            Spacer(Modifier.height(10.dp))
            BasicText("lukarne", style = Type.place(64.sp))
            Spacer(Modifier.height(6.dp))
            BasicText(stringResource(R.string.splash_tagline), style = Type.label(13.sp, Palette.FgFaint, weight = 400))
        }
    }
}
