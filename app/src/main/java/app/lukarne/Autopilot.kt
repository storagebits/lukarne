package app.lukarne

import android.app.Activity
import android.app.Instrumentation
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only remote control: presses keys from inside the app, as the real remote would, for frame measurements and
 * soak tests. `adb shell input keyevent` boots a Java VM per key on the Shield, which skews any frame measurement,
 * and the remote's input nodes are not writable from the adb shell.
 *
 *   --es autopilot "right right down ok wait back"   --ei autopilotDelay 400   --ei autopilotRepeat 100
 *
 * `wait` holds for one extra step. `random` picks among up/down/left/right.
 */
class Autopilot(
    @Suppress("unused") private val activity: Activity, private val steps: List<String>, private val delayMs: Long, private val repeat: Int) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        active = true
        // finally: a run cancelled with its activity (relaunch, recreate) must not leave BACK unable to quit.
        try {
            delay(6_000) // let the first screen settle
            repeat(repeat) { round ->
                for (s in steps) {
                    val key = when (s) {
                        "up" -> KeyEvent.KEYCODE_DPAD_UP
                        "down" -> KeyEvent.KEYCODE_DPAD_DOWN
                        "left" -> KeyEvent.KEYCODE_DPAD_LEFT
                        "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                        "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
                        "back" -> KeyEvent.KEYCODE_BACK
                        "menu" -> KeyEvent.KEYCODE_MENU
                        "play" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        "random" -> RANDOM.random()
                        "gc" -> { logMemory(); null }
                        else -> null
                    }
                    if (key != null) press(key)
                    delay(delayMs)
                }
                Log.i(TAG, "round ${round + 1}/$repeat done")
            }
            Log.i(TAG, "finished")
        } finally {
            active = false
        }
    }

    /** Live objects after a full GC: the number that tells a leak from a heap that simply hasn't been collected. */
    private suspend fun logMemory() = withContext(Dispatchers.Default) {
        val rt = Runtime.getRuntime()
        repeat(2) { System.gc(); System.runFinalization() }
        val javaKb = (rt.totalMemory() - rt.freeMemory()) / 1024
        val nativeKb = android.os.Debug.getNativeHeapAllocatedSize() / 1024
        Log.i(TAG, "memory after gc: java=${javaKb} KB native=${nativeKb} KB threads=${Thread.activeCount()}")
    }

    /** Injects through the real input pipeline (allowed for the app's own window), off the main thread. */
    private suspend fun press(code: Int) = withContext(Dispatchers.IO) {
        runCatching { instrumentation.sendKeyDownUpSync(code) }
            .onFailure { Log.w(TAG, "key $code not delivered: $it") }
    }

    private val instrumentation = Instrumentation()

    companion object {
        const val TAG = "Lukarne.Autopilot"

        /** While true, BACK on the whole-globe view doesn't quit: a soak run must not end on a stray BACK. */
        @Volatile var active = false
        private val RANDOM = listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
