package app.lukarne.data

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Tells a sideloaded install that a newer release is on GitHub. Nothing is downloaded or installed: the user fetches
 * the APK with the stable [DOWNLOAD_URL] (Downloader on the TV), signed with the same key, so it installs over this
 * one and keeps the settings. An install from Google Play never asks GitHub: the store updates it, and its policy
 * forbids pointing to another source.
 */
class Updates(context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    val installed: String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull() ?: "0"
    private val fromStore = installer(context) in STORES

    private val _available = MutableStateFlow(newer(prefs.getString("latest", null)))
    /** The newest published version when it is newer than [installed], else null. Known from the last check, offline too. */
    val available: StateFlow<String?> = _available

    private var latestApi = LATEST_API

    /**
     * Debug builds only: asks this URL instead of GitHub, at once, to test the notice against a stub `releases/latest`
     * answer (launch extra `--es updatesUrl http://localhost:8000/latest.json` after `adb reverse tcp:8000 tcp:8000`;
     * the debug manifest allows plain HTTP to localhost).
     */
    fun useStub(url: String) {
        latestApi = url
        prefs.edit().remove("checkedAt").apply()
    }

    /** At most every [CHECK_EVERY_MS]; the answer is kept for the launches in between. */
    fun check() {
        if (fromStore) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checkedAt", 0) < CHECK_EVERY_MS) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(latestApi).header("Accept", "application/vnd.github+json").build()
                http.newCall(req).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    val tag = json.decodeFromString<GitHubRelease>(r.body.string()).tag_name.removePrefix("v")
                    prefs.edit().putString("latest", tag).putLong("checkedAt", now).apply()
                    _available.value = newer(tag)
                    Log.i(TAG, "latest release $tag, installed $installed")
                }
            }.onFailure { Log.w(TAG, "release check failed: $it") }
        }
    }

    private fun newer(tag: String?) = tag?.takeIf { compareVersions(it, installed) > 0 }

    @Serializable
    private data class GitHubRelease(val tag_name: String)

    companion object {
        const val TAG = "Lukarne.Updates"
        private const val CHECK_EVERY_MS = 6 * 3_600_000L
        private const val LATEST_API = "https://api.github.com/repos/storagebits/lukarne/releases/latest"
        /** Every release carries its APK under this name, so this link always serves the newest one. */
        const val DOWNLOAD_URL = "github.com/storagebits/lukarne/releases/latest/download/lukarne.apk"
        private val STORES = setOf("com.android.vending", "com.amazon.venezia")
        private val json = Json { ignoreUnknownKeys = true }

        private fun installer(context: Context): String? = runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        }.getOrNull()

        /** "1.10.0" > "1.9.2"; missing parts count as 0, anything after a '-' is ignored. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pa = parts(a); val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}
