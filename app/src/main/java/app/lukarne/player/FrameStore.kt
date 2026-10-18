package app.lukarne.player

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Real frames of live cams, grabbed on this device while they play, to stand in for their stills.
 *
 * DelDOT has no still and other road stills lag, so the cleanest current picture of an HLS cam is the one this device
 * decoded itself. A frame is kept on disk (one JPEG per cam) and shown while it is recent. HLS only: YouTube's
 * terms forbid storing its content, so YouTube cams keep their official thumbnail.
 */
class FrameStore(private val dir: File, private val scope: CoroutineScope) {
    private val _frames = MutableStateFlow(scan())
    /** Cam id → when its frame was grabbed (epoch ms). */
    val frames: StateFlow<Map<String, Long>> = _frames

    /** Cam ids hold ':' and '/' (caltrans:D3/…): the file name is their URL-safe Base64, reversible by [scan]. */
    fun file(camId: String) = File(dir, Base64.encodeToString(camId.toByteArray(), B64) + ".jpg")

    /** The grab time of [camId]'s frame when it is younger than [maxAgeMs], else null. */
    fun recent(camId: String, maxAgeMs: Long): Long? =
        _frames.value[camId]?.takeIf { System.currentTimeMillis() - it < maxAgeMs }

    /** Stores [bitmap] as [camId]'s frame (scaled to [WIDTH] px, off the main thread). Takes ownership of it. */
    fun put(camId: String, bitmap: Bitmap) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (looksBlank(bitmap)) { Log.i(TAG, "blank frame for $camId, skipped"); return@runCatching }
                val h = (WIDTH * bitmap.height / bitmap.width.coerceAtLeast(1)).coerceAtLeast(1)
                val scaled = if (bitmap.width > WIDTH) Bitmap.createScaledBitmap(bitmap, WIDTH, h, true) else bitmap
                dir.mkdirs()
                val dst = file(camId)
                val tmp = File(dst.path + ".tmp")
                tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                check(tmp.renameTo(dst)) { "rename failed" }
                Log.i(TAG, "frame $camId ${scaled.width}×${scaled.height}")
                if (scaled !== bitmap) scaled.recycle()
                val at = System.currentTimeMillis()
                _frames.update { it + (camId to at) }
            }.onFailure { Log.w(TAG, "frame $camId not saved: $it") }
            bitmap.recycle()
        }
    }

    private fun scan(): Map<String, Long> {
        val now = System.currentTimeMillis()
        val out = HashMap<String, Long>()
        dir.listFiles()?.forEach { f ->
            when {
                !f.name.endsWith(".jpg") -> f.delete()
                now - f.lastModified() > KEEP_MS -> f.delete()
                else -> runCatching { String(Base64.decode(f.name.removeSuffix(".jpg"), B64)) }
                    .onSuccess { out[it] = f.lastModified() }
                    .onFailure { f.delete() }
            }
        }
        return out
    }

    /** A copy of a hardware video surface that came back empty (all one colour) is not a frame. */
    private fun looksBlank(b: Bitmap): Boolean {
        val first = b.getPixel(b.width / 2, b.height / 2)
        for (i in 1..7) for (j in 1..7) {
            if (b.getPixel(b.width * i / 8, b.height * j / 8) != first) return false
        }
        return true
    }

    companion object {
        const val TAG = "Lukarne.Frames"
        const val WIDTH = 640
        /** Frames older than this are deleted at startup: by then their light is wrong anyway. */
        const val KEEP_MS = 24 * 3_600_000L
        private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    }
}
