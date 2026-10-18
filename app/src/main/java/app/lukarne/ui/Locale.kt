package app.lukarne.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Bumped when the activity comes back after onStop released every player: the screens remount and re-acquire. */
val LocalSession = staticCompositionLocalOf { 0 }

/** Bumped when an overlay closes, so the screen underneath takes the remote's focus back. */
val LocalFocusEpoch = staticCompositionLocalOf { 0 }

/** True while a sheet (leave, Settings) is open over the screens: the sheet holds the remote. */
val LocalOverlayOpen = compositionLocalOf { false }

/**
 * A screen takes the remote when it appears and when an overlay closes, never while a sheet is open: the screens
 * remount behind an open sheet when the app comes back from the TV home (LocalSession), and a screen taking the
 * focus then left the sheet on screen with every key going to the globe behind it: Quit did nothing.
 */
@Composable
fun TakeRemote(requester: FocusRequester) {
    val overlay = LocalOverlayOpen.current
    LaunchedEffect(LocalFocusEpoch.current, overlay) { if (!overlay) requester.requestFocus() }
}

/** The UI language is an app setting (English by default), independent of the TV's system language. */
val LocalAppLocale = staticCompositionLocalOf<Locale> { Locale.ENGLISH }

@Composable
fun ProvideAppLocale(locale: Locale, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val config = remember(baseConfig, locale) { Configuration(baseConfig).apply { setLocale(locale) } }
    val context = remember(base, config) { base.createConfigurationContext(config) }
    CompositionLocalProvider(
        LocalContext provides context, LocalConfiguration provides config, LocalAppLocale provides locale,
        content = content,
    )
}
