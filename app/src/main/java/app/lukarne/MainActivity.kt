package app.lukarne

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.lukarne.ui.LukarneRoot
import app.lukarne.ui.ProvideAppLocale
import app.lukarne.ui.LocalSession
import app.lukarne.ui.Screen
import androidx.compose.runtime.mutableIntStateOf
import app.lukarne.ui.Splash
import androidx.compose.runtime.collectAsState
import app.lukarne.ui.theme.Palette
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val SPLASH_MS = 2_200L
/** Back from the TV home after this long: start over behind the logo, with the catalogue downloaded meanwhile. */
private const val FRESH_START_AFTER_MS = 30 * 60_000L

class MainActivity : ComponentActivity() {
    private var session by mutableIntStateOf(0)
    private var stopped = false
    private var stoppedAt = 0L
    private var relaunch by mutableIntStateOf(0)
    private var freshStartAfterMs = FRESH_START_AFTER_MS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val graph = (application as LukarneApp).graph
        // Launch extras (see README): --es place <id>, --es cam <id>, --ez demo true, --ei maxLive <n>, --es locale fr.
        val extras = intent.extras
        extras?.getInt("maxLive", 0)?.takeIf { it > 0 }?.let { graph.hls.maxPlayers = it }
        val forcedLocale = extras?.getString("locale")?.let(Locale::forLanguageTag)
        val startCam = extras?.getString("cam")
        val startPlace = extras?.getString("place")
        val startDemo = extras?.getBoolean("demo", false) == true
        extras?.getInt("freshAfterS", 0)?.takeIf { it > 0 }?.let { freshStartAfterMs = it * 1000L }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            extras?.getString("updatesUrl")?.let(graph.updates::useStub)
        }
        // Only reachable through `adb shell am start` extras; kept in release so frames are measured on the real build.
        extras?.getString("autopilot")?.let { steps ->
            Autopilot(
                this, steps.split(' ', ',').filter { it.isNotBlank() },
                delayMs = extras.getInt("autopilotDelay", 400).toLong(),
                repeat = extras.getInt("autopilotRepeat", 1),
            ).start(lifecycleScope)
        }
        setContent {
            CompositionLocalProvider(LocalGraph provides graph, LocalSession provides session) {
                val settings by graph.settings.settings.collectAsState()
                ProvideAppLocale(forcedLocale ?: settings.locale) {
                    // Every launch shows the logo for at least SPLASH_MS, even when loading is faster (~0.5 s cold,
                    // instant when Android kept the process after "Quit"). Coming back from the TV home screen
                    // resumes where it was, unless it was away FRESH_START_AFTER_MS: then it starts over (relaunch).
                    var ready by remember { mutableStateOf(savedInstanceState != null) }
                    LaunchedEffect(relaunch) {
                        if (relaunch > 0) ready = false
                        if (!ready) {
                            val t0 = SystemClock.uptimeMillis()
                            withContext(Dispatchers.Default) { graph.loadGeometry() }
                            // A catalogue published since the last launch is used now if it arrives behind the
                            // logo; otherwise it finishes in the background and the next launch takes it.
                            withTimeoutOrNull(SPLASH_MS - 600 - (SystemClock.uptimeMillis() - t0)) {
                                graph.refreshCatalog().await()
                            }
                            withContext(Dispatchers.Default) { graph.reloadCatalog() }
                            delay(SPLASH_MS - (SystemClock.uptimeMillis() - t0))
                            ready = true
                        }
                    }
                    Crossfade(ready, animationSpec = tween(600), label = "splash") { r ->
                        if (!r) {
                            Splash()
                        } else {
                            val c = graph.loadCatalog()
                            val cam = startCam?.let { id -> c.cams.firstOrNull { it.id == id } }
                            val place = cam?.cluster ?: startPlace
                            val start = if (startDemo) Screen.Demo else place?.let(c::place)?.let { Screen.Mosaic(it.id) } ?: Screen.Globe()
                            LukarneRoot(c, graph.loadGeometry(), start = start, startCam = cam, onExit = { finish() })
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (stopped) {
            stopped = false
            if (SystemClock.elapsedRealtime() - stoppedAt >= freshStartAfterMs) relaunch++ else session++
        }
        (application as LukarneApp).graph.run { refreshCatalog(); updates.check() }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        stoppedAt = SystemClock.elapsedRealtime()
        // Nothing plays behind the TV's home screen.
        val graph = (application as LukarneApp).graph
        graph.hls.releaseAll()
        graph.youtube.stop()
    }
}
