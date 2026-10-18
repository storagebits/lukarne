package app.lukarne.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.delay

/**
 * A webcam still that refreshes itself every [periodMs]. The new frame replaces the old one only once it has
 * fully loaded, so a refresh never flashes and a failed refresh keeps the last good frame on screen.
 * [onUnavailable] fires if the very first load fails (dead cam).
 */
@Composable
fun RefreshingStill(
    url: String,
    periodMs: Long,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxPx: Int = 960,
    onUnavailable: () -> Unit = {},
) {
    val context = LocalContext.current
    val unavailable by rememberUpdatedState(onUnavailable)
    var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, periodMs, maxPx) {
        val loader = SingletonImageLoader.get(context)
        var n = 0
        while (true) {
            val fresh = n > 0
            val request = ImageRequest.Builder(context)
                .data(if (fresh) bust(url, System.currentTimeMillis() / periodMs) else url)
                .size(maxPx, maxPx)
                .allowHardware(false)
                // Refreshes are throwaway: keep them out of the caches so they don't evict real stills.
                .memoryCachePolicy(if (fresh) CachePolicy.DISABLED else CachePolicy.ENABLED)
                .diskCachePolicy(if (fresh) CachePolicy.DISABLED else CachePolicy.ENABLED)
                .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                frame = result.image.toBitmap().asImageBitmap()
            } else if (frame == null) {
                unavailable()
            }
            n++
            delay(periodMs)
        }
    }
    frame?.let { Image(it, contentDescription = null, contentScale = contentScale, modifier = modifier) }
}

private fun bust(url: String, token: Long) = url + (if ('?' in url) "&" else "?") + "_lk=$token"

@Composable
fun StillFill(url: String, periodMs: Long, onUnavailable: () -> Unit = {}) =
    RefreshingStill(url, periodMs, Modifier.fillMaxSize(), onUnavailable = onUnavailable)
