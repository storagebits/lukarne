package app.lukarne.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One long-lived YouTube IFrame player (a WebView). Creating a WebView and booting the IFrame API costs
 * ~1–2 s on the Shield, so the view is created once and moved between screens; switching cams is a
 * `loadVideo` on the warm player. The WebView never takes focus: the remote stays with Compose.
 */
class YouTubeStage(context: Context, private val health: StreamHealth) {
    enum class State { IDLE, LOADING, PLAYING, FAILED }

    val view: YouTubePlayerView = YouTubePlayerView(context).apply {
        enableAutomaticInitialization = false
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // Fill whatever box hosts it (the view defaults to a 16:9 wrap that needs params to exist first).
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private var player: YouTubePlayer? = null
    private var pendingId: String? = null
    private var currentId: String? = null
    private var loadStartedAt = 0L
    private var retried = false
    /** UNSTARTED seen since the last load: from then on, PLAYING is about the video we asked for. */
    private var startedSinceLoad = false
    private var audible = false

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    private val _playingId = MutableStateFlow<String?>(null)
    /** The cam whose video is actually on screen (not just requested). */
    val playingId: StateFlow<String?> = _playingId

    init {
        val options = IFramePlayerOptions.Builder(context)
            .controls(0).rel(0).ivLoadPolicy(3).ccLoadPolicy(0).autoplay(1).mute(1).fullscreen(0)
            .build()
        view.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                player = youTubePlayer
                Log.i(TAG, "IFrame ready")
                pendingId?.let { pendingId = null; load(it) }
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                // A loaded video always reports UNSTARTED first: a PLAYING before that is the previous video's.
                if (state == PlayerConstants.PlayerState.UNSTARTED) startedSinceLoad = true
                if (state != PlayerConstants.PlayerState.PLAYING || _state.value != State.LOADING || !startedSinceLoad) return
                Log.i(TAG, "playing $currentId in ${SystemClock.elapsedRealtime() - loadStartedAt} ms")
                _state.value = State.PLAYING
                _playingId.value = currentId
                currentId?.let { health.markAlive("yt:$it") }
                if (audible) youTubePlayer.unMute() else youTubePlayer.mute()
            }

            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                Log.w(TAG, "error $currentId: $error")
                _state.value = State.FAILED
                currentId?.let { health.markDead("yt:$it", error.name) }
            }
        }, true, options)
        // A load sent while the view is out of the window (between two hosts) can leave the page believing it is
        // hidden, and the video never starts: such loads wait here for the view to be back on screen.
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.post { if (v.isAttachedToWindow) pendingId?.let { pendingId = null; load(it) } }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    private val _owner = MutableStateFlow<Any?>(null)
    /**
     * The screen element currently holding the player. During a crossfade two screens show the same cam; only
     * the latest claimant may stop the player or host the view, so the outgoing screen can't kill the incoming one.
     */
    val owner: StateFlow<Any?> = _owner

    fun claim(owner: Any, videoId: String, audible: Boolean) {
        _owner.value = owner
        setAudible(audible)
        load(videoId)
    }

    fun release(owner: Any) {
        if (_owner.value !== owner) return
        _owner.value = null
        setAudible(false)
        stop()
    }

    /** Loads [videoId]; a no-op when it is already the current video. */
    private fun load(videoId: String) {
        // Already this video (a screen handing it to the next one): keep it playing.
        if (videoId == currentId && pendingId == null && _state.value != State.FAILED && _state.value != State.IDLE) return
        val p = player
        if (p == null || !view.isAttachedToWindow) {
            pendingId = videoId
            loadStartedAt = 0   // no timeout while waiting
            _state.value = State.LOADING
            _playingId.value = null
            return
        }
        pendingId = null
        currentId = videoId
        loadStartedAt = SystemClock.elapsedRealtime()
        retried = false
        startedSinceLoad = false
        _state.value = State.LOADING
        _playingId.value = null
        p.loadVideo(videoId, 0f)
    }

    /** Called periodically: a video that has not started after [timeoutMs] counts as dead. */
    fun sweepTimeout(timeoutMs: Long) {
        // Halfway to the timeout, ask once more: a stuck IFrame (left UNSTARTED) usually starts on a second load.
        val p = player
        if (_state.value == State.LOADING && p != null && loadStartedAt > 0 && !retried &&
            SystemClock.elapsedRealtime() - loadStartedAt > timeoutMs / 2 && view.isAttachedToWindow
        ) {
            retried = true
            Log.w(TAG, "no start for $currentId, loading again")
            currentId?.let { p.loadVideo(it, 0f) }
        }
        if (_state.value == State.LOADING && player != null && loadStartedAt > 0 &&
            SystemClock.elapsedRealtime() - loadStartedAt > timeoutMs
        ) {
            Log.w(TAG, "timeout $currentId")
            _state.value = State.FAILED
            currentId?.let { health.markDead("yt:$it", "timeout") }
            player?.pause()
        }
    }

    private fun setAudible(on: Boolean) {
        audible = on
        player?.let { if (on) it.unMute() else it.mute() }
    }

    /** Stops playback (keeps the warm WebView) when no screen shows YouTube. */
    fun stop() {
        _owner.value = null
        player?.pause()
        currentId = null
        pendingId = null
        _playingId.value = null
        _state.value = State.IDLE
    }

    fun detach() { (view.parent as? ViewGroup)?.removeView(view) }

    fun release() { detach(); view.release() }

    companion object { const val TAG = "Lukarne.YT" }
}
