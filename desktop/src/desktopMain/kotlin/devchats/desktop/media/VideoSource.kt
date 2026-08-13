package devchats.desktop.media

import com.github.sarxos.webcam.Webcam
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage

/** Источник видео-кадров (камера или экран). Все методы безопасны при отсутствии устройства. */
interface VideoSource {
    /** Один кадр (уже масштабированный), либо null, если источник недоступен. */
    fun capture(): BufferedImage?

    fun close() {}
}

/** Захват экрана через java.awt.Robot (только для LAN — один кадр за раз). */
class ScreenVideoSource(
    private val maxWidth: Int = 1280,
    private val maxHeight: Int = 720,
) : VideoSource {

    private val robot: Robot? = runCatching { Robot() }.getOrNull()
    private val bounds: Rectangle? = runCatching { Rectangle(Toolkit.getDefaultToolkit().screenSize) }.getOrNull()

    override fun capture(): BufferedImage? {
        val r = robot ?: return null
        val b = bounds ?: return null
        return runCatching { VideoCodec.scale(r.createScreenCapture(b), maxWidth, maxHeight) }.getOrNull()
    }
}

/** Захват веб-камеры через webcam-capture (JNA: DirectShow/V4L2/QTKit). */
class WebcamVideoSource(
    private val maxWidth: Int = 640,
    private val maxHeight: Int = 360,
) : VideoSource {

    private val webcam: Webcam? by lazy {
        runCatching { Webcam.getDefault() }.getOrNull()?.takeIf { it.isOpen }
            ?: runCatching { Webcam.getDefault()?.also { it.open() } }.getOrNull()
    }

    override fun capture(): BufferedImage? {
        val cam = webcam ?: return null
        return runCatching { cam.image?.let { VideoCodec.scale(it, maxWidth, maxHeight) } }.getOrNull()
    }

    override fun close() {
        webcam?.close()
    }
}

/** Источник по виду трансляции: камера или экран. */
fun videoSourceFor(kind: String): VideoSource? = when (kind) {
    devchats.protocol.VIDEO_KIND_SCREEN -> ScreenVideoSource()
    devchats.protocol.VIDEO_KIND_CAMERA -> WebcamVideoSource()
    else -> null
}
