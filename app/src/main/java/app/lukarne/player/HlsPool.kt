package app.lukarne.player

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.lukarne.model.Webcam
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/**
 * Owns every ExoPlayer in the app. Players are keyed by webcam id and reference-counted, so the mosaic tile and
 * the fullscreen view of the same cam share one decoder. A hard [maxPlayers] cap protects the device's
 * decoders: when it is reached, [acquire] returns null and the caller shows a still instead.
 */
@OptIn(UnstableApi::class)
class HlsPool(
    private val context: Context,
    http: OkHttpClient,
    private val health: StreamHealth,
    var maxPlayers: Int = deviceBudget(context),
) {
    enum class State { LOADING, PLAYING, FAILED }

    class Handle internal constructor(val cam: Webcam, val player: ExoPlayer) {
        internal var refs = 0
        internal var lingerToken: Any? = null
        internal var listener: Player.Listener? = null
        internal val startedAt = SystemClock.elapsedRealtime()
        /** Buffered and ready to play. A prefetched player has no surface, so it never renders a first frame. */
        internal var ready = false
        val state = MutableStateFlow(State.LOADING)
        /** Time to first frame in ms, once known. */
        var firstFrameMs: Long = -1; internal set
    }

    private val handles = LinkedHashMap<String, Handle>()
    /** Stopped players kept for reuse: building and releasing an ExoPlayer costs ~50–150 ms of main thread. */
    private val idle = ArrayDeque<ExoPlayer>()
    private val main = Handler(Looper.getMainLooper())
    private val mediaSourceFactory = HlsMediaSource.Factory(OkHttpDataSource.Factory(http))
        .setAllowChunklessPreparation(true)

    val activeCount: Int get() = handles.size

    private val _activeIds = MutableStateFlow<Set<String>>(emptySet())
    val activeIds: StateFlow<Set<String>> = _activeIds

    /** Returns a player for [cam], creating it if needed, or null when the decoder budget is spent. */
    fun acquire(cam: Webcam, lowRes: Boolean): Handle? {
        handles[cam.id]?.let {
            it.refs++
            it.lingerToken?.let(main::removeCallbacksAndMessages); it.lingerToken = null
            applyRes(it.player, lowRes)
            return it
        }
        if (handles.size >= maxPlayers) evictLingering() ?: return null
        val player = idle.removeLastOrNull() ?: build()
        val h = Handle(cam, player).also { it.refs = 1 }
        player.volume = 0f
        player.repeatMode = Player.REPEAT_MODE_OFF
        applyRes(player, lowRes)
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !h.ready) { h.ready = true; health.markAlive(cam.id) }
            }

            override fun onRenderedFirstFrame() {
                h.firstFrameMs = SystemClock.elapsedRealtime() - h.startedAt
                h.state.value = State.PLAYING
                health.markAlive(cam.id)
                Log.i(TAG, "first frame ${cam.id} in ${h.firstFrameMs} ms (active=${handles.size})")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "error ${cam.id}: ${error.errorCodeName}")
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                h.state.value = State.FAILED
                health.markDead(cam.id, error.errorCodeName)
            }
        }
        h.listener = listener
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(cam.stream))
        player.playWhenReady = true
        player.prepare()
        handles[cam.id] = h
        publish()
        return h
    }

    /**
     * Drops a reference. The player lingers [LINGER_MS] before being freed: when a prefetched neighbour becomes
     * the cam on screen, Compose disposes the prefetch before the new view acquires it, and the buffer must
     * survive that gap. A lingering player is the first to go when the budget is needed.
     */
    fun release(h: Handle) {
        if (--h.refs > 0) return
        val token = Any()
        h.lingerToken = token
        h.player.volume = 0f
        main.postAtTime({ if (h.refs == 0) free(h) }, token, SystemClock.uptimeMillis() + LINGER_MS)
    }

    private fun free(h: Handle) {
        h.lingerToken?.let(main::removeCallbacksAndMessages); h.lingerToken = null
        if (handles[h.cam.id] !== h) return
        handles.remove(h.cam.id)
        h.listener?.let(h.player::removeListener)
        // stop() frees the decoder and the buffers; the player object is kept for the next cam.
        h.player.stop()
        h.player.clearMediaItems()
        if (idle.size < maxPlayers) idle.addLast(h.player) else h.player.release()
        publish()
        Log.i(TAG, "freed ${h.cam.id} (active=${handles.size}, idle=${idle.size})")
    }

    private fun build(): ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Live cams: start as soon as one segment is in, keep the buffer small (memory, latency).
                .setBufferDurationsMs(4_000, 12_000, 500, 1_000)
                .build(),
        )
        .setMediaSourceFactory(mediaSourceFactory)
        .build()

    private fun evictLingering(): Unit? {
        val victim = handles.values.firstOrNull { it.refs == 0 } ?: return null
        free(victim)
        return Unit
    }

    /** Marks streams that have neither buffered nor produced a frame within [timeoutMs] as failed. Called periodically. */
    fun sweepTimeouts(timeoutMs: Long) {
        val now = SystemClock.elapsedRealtime()
        for (h in handles.values) {
            if (h.state.value == State.LOADING && !h.ready && now - h.startedAt > timeoutMs) {
                Log.w(TAG, "timeout ${h.cam.id}")
                h.state.value = State.FAILED
                health.markDead(h.cam.id, "timeout")
                h.player.stop()
            }
        }
    }

    fun setAudible(h: Handle, audible: Boolean) { h.player.volume = if (audible) 1f else 0f }

    fun releaseAll() {
        main.removeCallbacksAndMessages(null)
        handles.values.forEach { it.player.release() }
        handles.clear()
        idle.forEach { it.release() }
        idle.clear()
        publish()
    }

    private fun publish() { _activeIds.value = handles.keys.toSet() }

    /** Tiles ask for ≤ 480p so several decoders fit; fullscreen lifts the cap. */
    private fun applyRes(player: ExoPlayer, lowRes: Boolean) {
        val p = player.trackSelectionParameters.buildUpon()
        if (lowRes) p.setMaxVideoSize(854, 480) else p.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        player.trackSelectionParameters = p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    companion object {
        const val TAG = "Lukarne.Hls"
        const val LINGER_MS = 1_500L
        /** Measured on the Shield (3 GB): 9 concurrent 480p decoders work but cost 113 % CPU; 8 is the ceiling. */
        private const val MAX_PLAYERS = 8

        /**
         * How many players this device can run: the Shield's 8, fewer where the hardware H.264 decoder admits fewer
         * instances or memory is short (a Chromecast with Google TV HD has 1.5 GB). Every page keeps 2 for the focus.
         */
        fun deviceBudget(context: Context): Int {
            val instances = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MimeTypes.VIDEO_H264, true) } && it.isHardware() }
                .maxOfOrNull { runCatching { it.getCapabilitiesForType(MimeTypes.VIDEO_H264).maxSupportedInstances }.getOrDefault(0) }
                ?: 0
            val am = context.getSystemService(ActivityManager::class.java)
            val ram = ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem
            val byRam = when {
                am.isLowRamDevice || ram < 1_600_000_000L -> 4
                ram < 2_500_000_000L -> 6
                else -> MAX_PLAYERS
            }
            // An unknown instance count (0) does not limit; a known one keeps one decoder spare for fullscreen.
            val byCodec = if (instances > 0) instances - 1 else MAX_PLAYERS
            return minOf(MAX_PLAYERS, byRam, byCodec).coerceAtLeast(3).also {
                Log.i(TAG, "decoder budget $it (hw H.264 instances $instances, RAM ${ram / 1_000_000} MB)")
            }
        }

        private fun MediaCodecInfo.isHardware(): Boolean =
            if (Build.VERSION.SDK_INT >= 29) isHardwareAccelerated
            else !name.startsWith("OMX.google.") && !name.startsWith("c2.android.")
    }
}
