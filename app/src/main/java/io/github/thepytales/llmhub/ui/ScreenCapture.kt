package io.github.thepytales.llmhub.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * A screenshot of the app for the checks on the device (AP-133), as PNG; null when this platform cannot take one.
 * Android: what the app's window shows (the desktop version captures the screen the window is on).
 */
@Composable
fun rememberScreenCapture(): (suspend () -> ByteArray?)? {
    val view = LocalView.current
    return remember(view) {
        {
            withContext(Dispatchers.Main) {
                runCatching {
                    val root = view.rootView
                    val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bmp))
                    ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                }.getOrNull()
            }
        }
    }
}
