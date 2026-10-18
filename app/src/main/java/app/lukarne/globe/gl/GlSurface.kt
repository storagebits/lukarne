package app.lukarne.globe.gl

import android.content.Context
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer

/**
 * A SurfaceView with its own GLES 2 render thread, paced by vsync (Choreographer on that thread).
 * Renders every frame while the surface exists; [Renderer.onDraw] reads the latest camera itself.
 *
 * SurfaceView, not TextureView: a TextureView makes HWUI re-composite the window for every GL frame, which on the
 * Shield cost ~20 ms UI frames and dropped GL frames; the SurfaceView's buffer goes straight to SurfaceFlinger
 * (UI p90 10 ms, GL 60 fps with no gap). Compose content is drawn above it in the window.
 */
class GlSurface(context: Context, private val renderer: Renderer) : SurfaceView(context), SurfaceHolder.Callback {

    interface Renderer {
        fun onCreated()
        fun onSize(width: Int, height: Int)
        fun onDraw()
        fun onDestroyed()
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var egl: Egl? = null
    @Volatile private var running = false

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.OPAQUE)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (thread != null) { handler?.post { renderer.onSize(width, height) }; return }
        val surface = holder.surface
        val t = HandlerThread("globe-gl").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        running = true
        h.post {
            egl = Egl(surface).also { it.makeCurrent() }
            renderer.onCreated()
            renderer.onSize(width, height)
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    // Frame stats, logged every 5 s: the GL thread is invisible to `dumpsys gfxinfo`.
    private var statFrames = 0; private var statStart = 0L; private var statLast = 0L
    private var statMaxGap = 0L; private var statDraw = 0L; private var statSlow = 0

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val t0 = System.nanoTime()
            renderer.onDraw()
            egl?.swap()
            stats(frameTimeNanos, System.nanoTime() - t0)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stats(vsync: Long, drawNs: Long) {
        if (statStart == 0L) { statStart = vsync; statLast = vsync }
        val gap = vsync - statLast
        if (gap > statMaxGap) statMaxGap = gap
        if (gap > 20_000_000) statSlow++
        statLast = vsync
        statFrames++; statDraw += drawNs
        if (vsync - statStart >= 5_000_000_000) {
            Log.i(
                "Lukarne.Globe",
                "gl: ${statFrames / 5} fps, ${statSlow} gaps >20 ms, max gap ${statMaxGap / 1_000_000} ms, " +
                    "draw+swap ${statDraw / statFrames / 1000} µs avg",
            )
            statStart = vsync; statFrames = 0; statMaxGap = 0; statDraw = 0; statSlow = 0
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        val h = handler ?: return
        val t = thread
        h.post {
            Choreographer.getInstance().removeFrameCallback(frame)
            renderer.onDestroyed()
            egl?.release()
            egl = null
            t?.quitSafely()
        }
        // The thread releases the EGL surface before the SurfaceTexture goes; wait for it (bounded).
        t?.join(500)
        handler = null
        thread = null
    }

    /** Minimal EGL14 setup: GLES 2, RGBA8888, 4× MSAA when available (smooth hairlines and point edges). */
    private class Egl(surface: android.view.Surface) {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val context: EGLContext
        val eglSurface: EGLSurface

        init {
            val v = IntArray(2)
            EGL14.eglInitialize(display, v, 0, v, 1)
            val config = choose(4) ?: choose(0) ?: error("no EGL config")
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        }

        private fun choose(samples: Int): EGLConfig? {
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0, EGL14.EGL_SAMPLES, samples,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0)
            if (n[0] > 0) Log.i("Lukarne.Globe", "EGL config with $samples× MSAA")
            return if (n[0] > 0) configs[0] else null
        }

        fun makeCurrent() = EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
        fun swap() = EGL14.eglSwapBuffers(display, eglSurface)
        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            // No eglTerminate: the default display is shared with HWUI and WebView in this process.
            EGL14.eglReleaseThread()
        }
    }
}
