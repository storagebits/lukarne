package app.lukarne.data

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** UI languages, in the order the setting cycles through them. */
val LANGUAGES = listOf("en", "fr", "es")

/** How long the demo stays on each cam, in the order the setting cycles through them. */
val DEMO_SECONDS = listOf(20, 30, 60, 120, 300)

/**
 * UI language (English by default), temperature unit, whether still-image cams show up at all (off by default:
 * lukarne is about live video; stills are an opt-in complement), whether a cam left from fullscreen keeps playing
 * in a corner ([mini]), and how long the demo shows each cam.
 */
data class Settings(
    val language: String = "en",
    val fahrenheit: Boolean = false,
    val stills: Boolean = false,
    val mini: Boolean = false,
    val demoSeconds: Int = 30,
) {
    val locale: Locale get() = Locale.forLanguageTag(language.takeIf { it in LANGUAGES } ?: "en")
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(
        Settings(
            prefs.getString("language", "en") ?: "en",
            prefs.getBoolean("fahrenheit", false),
            prefs.getBoolean("stills", false),
            prefs.getBoolean("mini", false),
            prefs.getInt("demoSeconds", 30).takeIf { it in DEMO_SECONDS } ?: 30,
        ),
    )
    val settings: StateFlow<Settings> = _settings

    fun update(s: Settings) {
        _settings.value = s
        prefs.edit().putString("language", s.language).putBoolean("fahrenheit", s.fahrenheit)
            .putBoolean("stills", s.stills).putBoolean("mini", s.mini).putInt("demoSeconds", s.demoSeconds).apply()
    }
}
