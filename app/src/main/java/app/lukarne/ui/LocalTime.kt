package app.lukarne.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** Wall-clock time, recomposing on each minute boundary. One per screen, shared by every tile. */
@Composable
fun rememberMinuteClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000 + 50)
        }
    }
    return now
}

@Composable
fun localTimeFormatter(): DateTimeFormatter {
    val ctx = LocalContext.current
    val locale = LocalAppLocale.current
    val is24 = DateFormat.is24HourFormat(ctx) || locale.language != "en"
    return remember(is24, locale) { DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", locale) }
}

fun formatLocalTime(nowMs: Long, tz: String, f: DateTimeFormatter): String =
    runCatching { f.format(Instant.ofEpochMilli(nowMs).atZone(ZoneId.of(tz))) }.getOrDefault("")

fun countryName(cc: String, locale: Locale): String =
    if (cc.isBlank()) "" else Locale.Builder().setRegion(cc).build().getDisplayCountry(locale)
