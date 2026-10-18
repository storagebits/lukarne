package app.lukarne.player

import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Remembers which streams failed on this device, so they drop out of mosaics and of "Surprise me" instead of
 * failing again. A dead mark expires after [ttlMs]: cams come back (night outages, restarts of YouTube lives).
 * `adb pull /sdcard/Android/data/app.lukarne/files/dead.json` fetches them; `tools/catalog/build.sh --dead` drops them.
 */
class StreamHealth(private val file: File, private val ttlMs: Long = 6 * 3_600_000L) {
    @Serializable
    data class Dead(val at: Long, val reason: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val _dead = MutableStateFlow(load())
    val dead: StateFlow<Map<String, Dead>> = _dead

    fun isDead(id: String): Boolean {
        val d = _dead.value[id] ?: return false
        return System.currentTimeMillis() - d.at < ttlMs
    }

    fun markDead(id: String, reason: String) {
        Log.w(TAG, "dead $id ($reason)")
        _dead.value = _dead.value + (id to Dead(System.currentTimeMillis(), reason))
        save()
    }

    fun markAlive(id: String) {
        if (id !in _dead.value) return
        _dead.value = _dead.value - id
        save()
    }

    private fun load(): Map<String, Dead> = runCatching {
        json.decodeFromString<Map<String, Dead>>(file.readText())
    }.getOrDefault(emptyMap())

    private fun save() {
        runCatching { file.writeText(json.encodeToString(_dead.value)) }
    }

    companion object { const val TAG = "Lukarne.Health" }
}
