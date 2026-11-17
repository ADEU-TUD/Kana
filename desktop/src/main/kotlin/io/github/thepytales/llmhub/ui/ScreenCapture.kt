package io.github.thepytales.llmhub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * A screenshot for the checks on the device (AP-133), as PNG: the whole screen the app's window is on, in full
 * resolution (150 % scaling included), so another window or the place in the app can be in it. Null without a screen.
 */
@Composable
fun rememberScreenCapture(): (suspend () -> ByteArray?)? = remember {
    if (GraphicsEnvironment.isHeadless()) null else ({ withContext(Dispatchers.IO) { captureScreen() } })
}

internal fun captureScreen(): ByteArray? = runCatching {
    val window = java.awt.Window.getWindows().filter { it.isShowing }.maxByOrNull { it.width * it.height }
    val config = window?.graphicsConfiguration ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val shot = Robot().createMultiResolutionScreenCapture(config.bounds)
    val best = shot.resolutionVariants.maxByOrNull { it.getWidth(null) * it.getHeight(null) } ?: return@runCatching null
    val img = best as? BufferedImage ?: BufferedImage(best.getWidth(null), best.getHeight(null), BufferedImage.TYPE_INT_RGB).also {
        it.createGraphics().apply { drawImage(best, 0, 0, null); dispose() }
    }
    ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
}.getOrNull()
