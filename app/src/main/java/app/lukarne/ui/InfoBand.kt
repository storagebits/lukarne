package app.lukarne.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lukarne.LocalGraph
import app.lukarne.R
import app.lukarne.data.Weather
import app.lukarne.data.WeatherSource
import app.lukarne.model.Webcam
import app.lukarne.ui.theme.Palette
import app.lukarne.ui.theme.Type
import kotlin.math.roundToInt

/**
 * The discreet caption of a fullscreen cam: place, country, local time, weather, source credit. Over the picture on
 * a gradient, or ([overVideo] false, for YouTube, whose player nothing may cover) on its own strip below it.
 */
@Composable
fun InfoBand(cam: Webcam, position: Int?, total: Int, modifier: Modifier = Modifier, overVideo: Boolean = true) {
    val graph = LocalGraph.current
    val settings by graph.settings.settings.collectAsState()
    val locale = LocalAppLocale.current
    val now = rememberMinuteClock()
    var weather by remember(cam.id) { mutableStateOf<Weather?>(null) }
    LaunchedEffect(cam.id) { weather = graph.weather.at(cam.lat, cam.lon) }

    Column(
        modifier.fillMaxWidth()
            .then(
                if (overVideo) Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Ink.copy(alpha = 0.85f))))
                else Modifier.background(Palette.Ink),
            )
            .padding(start = 48.dp, end = 48.dp, top = if (overVideo) 56.dp else 18.dp, bottom = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(
                cam.place, style = Type.place(34.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 24.dp),
            )
            weather?.let { w ->
                val t = if (settings.fahrenheit) w.celsius * 9 / 5 + 32 else w.celsius
                BasicText(
                    "${t.roundToInt()}°  ${WeatherSource.label(w.code, locale.language)}",
                    style = Type.label(15.sp, Palette.FgMuted, weight = 400),
                    modifier = Modifier.padding(end = 22.dp, bottom = 6.dp),
                )
            }
            BasicText(formatLocalTime(now, cam.tz, localTimeFormatter()), style = Type.numeric(30.sp, weight = 300))
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.Start) {
            BasicText(countryName(cam.country, locale).uppercase(locale), style = Type.label())
            Spacer(Modifier.width(16.dp))
            if (position != null) BasicText("$position / $total", style = Type.label(color = Palette.FgFaint))
            Spacer(Modifier.weight(1f))
            BasicText(sourceCredit(cam.source), style = Type.label(11.sp, Palette.FgFaint, weight = 400))
        }
    }
}

/** What each source asks to be credited as (Digitraffic: CC BY 4.0; YouTube: its content must say so). */
@Composable
fun sourceCredit(source: String): String = when (source) {
    "caltrans" -> "Caltrans"
    "deldot" -> "DelDOT"
    "digitraffic" -> "Fintraffic · CC BY 4.0"
    "youtube" -> "YouTube"
    else -> source
}
