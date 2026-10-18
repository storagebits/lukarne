package app.lukarne

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.staticCompositionLocalOf
import android.util.Log
import app.lukarne.data.Catalog
import app.lukarne.data.SettingsStore
import app.lukarne.data.Updates
import app.lukarne.data.WeatherSource
import app.lukarne.globe.GlobeGeometry
import app.lukarne.player.FrameStore
import app.lukarne.player.HlsPool
import app.lukarne.player.StreamHealth
import app.lukarne.player.YouTubeStage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class LukarneApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph; private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
        // Stills are small (≤ 640 px) and many: bound memory hard, the Shield has ~1 GB to spare.
        .memoryCache { MemoryCache.Builder().maxSizeBytes(48L * 1024 * 1024).build() }
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "stills")).maxSizeBytes(64L * 1024 * 1024).build() }
        .build()
}

/** Manual wiring: everything long-lived, created once per process. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    // App-specific external storage: `adb pull /sdcard/Android/data/app.lukarne/files/dead.json` works on the
    // release build (no run-as), and the catalogue pipeline takes that file with --dead.
    val health = StreamHealth(File(app.getExternalFilesDir(null) ?: app.filesDir, "dead.json"))
    val hls = HlsPool(app, http, health)
    val settings = SettingsStore(app)
    val weather = WeatherSource(http)
    val updates = Updates(app, http, scope)
    val youtube by lazy { YouTubeStage(app, health) }
    val frames = FrameStore(File(app.cacheDir, "frames"), scope)
    private val appContext = app
    @Volatile var catalog: Catalog? = null; private set

    @Volatile var geometry: GlobeGeometry? = null; private set

    fun loadGeometry(): GlobeGeometry = geometry ?: GlobeGeometry.load(appContext).also { geometry = it }

    /** The newest of the embedded catalogue and the last downloaded one; kept for the rest of the process. */
    fun loadCatalog(): Catalog = catalog ?: run {
        val t0 = SystemClock.elapsedRealtime()
        newestCatalog().also {
            catalog = it
            Log.i("Lukarne", "catalogue ${it.generated}: ${it.cams.size} cams, ${it.places.size} places, " +
                "${it.regions.size} regions in ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    /** Called behind the splash only: a new launch picks up a catalogue downloaded since the previous one. */
    fun reloadCatalog(): Catalog {
        catalog = null
        return loadCatalog()
    }

    private val downloaded = File(app.filesDir, "catalog.json")

    private fun newestCatalog(): Catalog {
        val bundled = Catalog.fromAsset(appContext)
        val fresh = if (downloaded.exists()) {
            runCatching { Catalog.parse(downloaded.readText()) }
                .onFailure { Log.w("Lukarne", "downloaded catalogue unusable: $it"); downloaded.delete() }
                .getOrNull()
        } else null
        // ISO-8601 UTC timestamps compare as strings. An APK built after the last download wins over it.
        return if (fresh != null && fresh.generated > bundled.generated) fresh else bundled
    }

    private var fetch: Deferred<Boolean>? = null
    private var fetchedAt = 0L

    /**
     * Downloads the catalogue that CI republishes every day (YouTube lives change id when a channel restarts its
     * stream), at most once an hour. It is used from the next launch, never swapped under a screen. Main thread.
     */
    fun refreshCatalog(): Deferred<Boolean> {
        fetch?.takeIf { it.isActive }?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (fetchedAt != 0L && now - fetchedAt < CATALOG_REFRESH_MS) return CompletableDeferred(false)
        fetchedAt = now
        return scope.async(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(CATALOG_URL).build()).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    val text = r.body.string()
                    val c = Catalog.parse(text)
                    val current = catalog?.generated ?: ""
                    val onDisk = runCatching { Catalog.parse(downloaded.readText()).generated }.getOrDefault("")
                    if (c.generated <= maxOf(current, onDisk)) return@use false
                    val tmp = File(downloaded.path + ".tmp")
                    tmp.writeText(text)
                    check(tmp.renameTo(downloaded)) { "rename failed" }
                    Log.i("Lukarne", "downloaded catalogue ${c.generated}: ${c.cams.size} cams")
                    true
                }
            }.onFailure { Log.w("Lukarne", "catalogue download failed: $it") }.getOrDefault(false)
        }.also { fetch = it }
    }

    init {
        // A cam that hasn't shown a frame after a few seconds is dead for this session: never wait on it.
        scope.launch {
            while (isActive) {
                delay(1_000)
                hls.sweepTimeouts(HLS_TIMEOUT_MS)
                youtube.sweepTimeout(YT_TIMEOUT_MS)
            }
        }
    }

    companion object {
        const val HLS_TIMEOUT_MS = 8_000L
        const val YT_TIMEOUT_MS = 12_000L
        /** Public repo written by .github/workflows/catalog.yml; it holds nothing but this file. */
        const val CATALOG_URL = "https://raw.githubusercontent.com/storagebits/lukarne-catalog/main/catalog.json"
        const val CATALOG_REFRESH_MS = 3_600_000L
    }
}

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("no graph") }
