package app.lukarne.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lukarne.LocalGraph
import app.lukarne.data.DEMO_SECONDS
import app.lukarne.data.LANGUAGES
import app.lukarne.data.Updates
import app.lukarne.R
import app.lukarne.ui.theme.Palette
import app.lukarne.ui.theme.Type

/**
 * BACK on the whole-globe view: instead of quitting outright, offer the demo, Settings (the Shield remote has no
 * Menu key) or Quit. BACK again closes it.
 */
@Composable
fun LeaveSheet(onDemo: () -> Unit, onSettings: () -> Unit, onQuit: () -> Unit, onClose: () -> Unit) {
    var row by remember { mutableIntStateOf(0) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    Box(
        Modifier.fillMaxSize().background(Palette.Ink.copy(alpha = 0.6f))
            .focusRequester(requester)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                // A held BACK opens the sheet on its first press; its repeats must not close it again at once.
                if (e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                when (e.key) {
                    Key.DirectionUp -> row = (row - 1).coerceAtLeast(0)
                    Key.DirectionDown -> row = (row + 1).coerceAtMost(2)
                    Key.DirectionCenter, Key.Enter -> when (row) { 0 -> onDemo(); 1 -> onSettings(); else -> onQuit() }
                    Key.Back, Key.Escape -> onClose()
                    else -> Unit
                }
                true
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(340.dp).background(Palette.Deep, RoundedCornerShape(16.dp)).padding(28.dp)) {
            BasicText("lukarne", style = Type.place(30.sp))
            Spacer(Modifier.height(20.dp))
            Choice(stringResource(R.string.leave_demo), row == 0)
            Spacer(Modifier.height(10.dp))
            Choice(stringResource(R.string.settings_title), row == 1)
            Spacer(Modifier.height(10.dp))
            Choice(stringResource(R.string.leave_quit), row == 2)
            Spacer(Modifier.height(16.dp))
            BasicText(stringResource(R.string.leave_hint), style = Type.label(11.sp, Palette.FgFaint, weight = 400))
        }
    }
}

@Composable
private fun Choice(label: String, focused: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth()
            .background(if (focused) Palette.Ocean else Palette.Deep, shape)
            .then(if (focused) Modifier.border(2.dp, Palette.LightHot, shape) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        BasicText(label, style = Type.numeric(17.sp, if (focused) Palette.Fg else Palette.FgMuted))
    }
}

/**
 * From the leave sheet (BACK on the globe) or Menu. Five rows, each changed with OK or LEFT/RIGHT, the focused
 * row's explanation under them, then credits. BACK or Menu closes. Changes apply live.
 */
@Composable
fun SettingsSheet(onClose: () -> Unit) {
    val store = LocalGraph.current.settings
    val s by store.settings.collectAsState()
    var row by remember { mutableIntStateOf(0) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }

    fun change(dir: Int) = when (row) {
        0 -> store.update(s.copy(language = LANGUAGES[(LANGUAGES.indexOf(s.language) + dir).mod(LANGUAGES.size)]))
        1 -> store.update(s.copy(fahrenheit = !s.fahrenheit))
        2 -> store.update(s.copy(stills = !s.stills))
        3 -> store.update(s.copy(mini = !s.mini))
        else -> store.update(s.copy(demoSeconds = DEMO_SECONDS[(DEMO_SECONDS.indexOf(s.demoSeconds) + dir).mod(DEMO_SECONDS.size)]))
    }

    Box(
        Modifier.fillMaxSize().background(Palette.Ink.copy(alpha = 0.55f))
            .focusRequester(requester)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                when (e.key) {
                    Key.DirectionUp -> row = (row - 1).coerceAtLeast(0)
                    Key.DirectionDown -> row = (row + 1).coerceAtMost(ROWS - 1)
                    Key.DirectionLeft -> change(-1)
                    Key.DirectionRight, Key.DirectionCenter, Key.Enter -> change(+1)
                    Key.Back, Key.Escape, Key.Menu -> onClose()
                    else -> Unit
                }
                true
            }
            .focusable(),
    ) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(380.dp)
                .background(Palette.Deep).padding(horizontal = 36.dp, vertical = 32.dp),
        ) {
            BasicText(stringResource(R.string.settings_title), style = Type.place(34.sp))
            Spacer(Modifier.height(20.dp))
            val onOff = { on: Boolean -> if (on) R.string.on else R.string.off }
            val rows = listOf(
                stringResource(R.string.settings_language) to when (s.language) { "fr" -> "Français"; "es" -> "Español"; else -> "English" },
                stringResource(R.string.settings_unit) to if (s.fahrenheit) "°F" else "°C",
                stringResource(R.string.settings_stills) to stringResource(onOff(s.stills)),
                stringResource(R.string.settings_mini) to stringResource(onOff(s.mini)),
                stringResource(R.string.settings_demo) to demoDuration(s.demoSeconds),
            )
            rows.forEachIndexed { i, (label, value) ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                Option(label, value, row == i)
            }
            val hint = when (row) {
                2 -> R.string.settings_stills_hint
                3 -> R.string.settings_mini_hint
                4 -> R.string.settings_demo_hint
                else -> null
            }
            Spacer(Modifier.height(10.dp))
            BasicText(
                hint?.let { stringResource(it) } ?: "",
                style = Type.label(11.sp, Palette.FgFaint, weight = 400).copy(letterSpacing = 0.sp, lineHeight = 16.sp),
                minLines = 3,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            BasicText(stringResource(R.string.settings_sources), style = Type.label(11.sp, Palette.Light))
            Spacer(Modifier.height(6.dp))
            BasicText(stringResource(R.string.settings_credits), style = Type.label(11.sp, Palette.FgMuted, weight = 400).copy(lineHeight = 17.sp, letterSpacing = 0.sp))
            Spacer(Modifier.height(14.dp))
            UpdateNote(Modifier, withInstalled = true)
        }
    }
}

private const val ROWS = 5

/**
 * "Version 1.1.0 available" and where to get it, when a sideloaded install is behind GitHub's latest release
 * (see Updates); in Settings, the installed version otherwise.
 */
@Composable
fun UpdateNote(modifier: Modifier, withInstalled: Boolean = false) {
    val updates = LocalGraph.current.updates
    val latest by updates.available.collectAsState()
    val v = latest
    when {
        v != null -> Column(modifier) {
            BasicText(stringResource(R.string.update_available, v), style = Type.label(12.sp, Palette.Light))
            Spacer(Modifier.height(3.dp))
            BasicText(stringResource(R.string.update_where, Updates.DOWNLOAD_URL),
                style = Type.label(11.sp, Palette.FgMuted, weight = 400).copy(letterSpacing = 0.sp, lineHeight = 16.sp))
        }
        withInstalled -> BasicText(stringResource(R.string.update_installed, updates.installed),
            style = Type.label(11.sp, Palette.FgFaint, weight = 400), modifier = modifier)
    }
}

/** 20 s, 1 min, 5 min. */
fun demoDuration(seconds: Int): String = if (seconds < 60) "$seconds s" else "${seconds / 60} min"

@Composable
private fun Option(label: String, value: String, focused: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth()
            .background(if (focused) Palette.Ocean else Palette.Deep, shape)
            .then(if (focused) Modifier.border(2.dp, Palette.LightHot, shape) else Modifier)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = Type.label(14.sp, Palette.FgMuted, weight = 400), modifier = Modifier.weight(1f))
        BasicText("‹  $value  ›", style = Type.numeric(17.sp, if (focused) Palette.Fg else Palette.FgMuted))
    }
}
