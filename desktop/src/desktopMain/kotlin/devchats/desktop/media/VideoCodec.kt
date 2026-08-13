package devchats.desktop.media

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Кодирование/декодирование видео-кадров (JPEG) и масштабирование. */
object VideoCodec {

    /** Кодирует кадр в JPEG (качество [quality], 0..1). */
    fun encodeJpeg(image: BufferedImage, quality: Float = 0.7f): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        writer.output = ImageIO.createImageOutputStream(out)
        val param = writer.defaultWriteParam
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = quality
        writer.write(null, IIOImage(image, null, null), param)
        writer.dispose()
        return out.toByteArray()
    }

    /** Декодирует JPEG в кадр; null при битых данных. */
    fun decodeJpeg(data: ByteArray): BufferedImage? =
        runCatching { ImageIO.read(ByteArrayInputStream(data)) }.getOrNull()

    /**
     * Масштабирует кадр так, чтобы он вписался в [maxWidth]×[maxHeight],
     * сохраняя пропорции (изображение не растягивается).
     */
    fun scale(image: BufferedImage, maxWidth: Int, maxHeight: Int): BufferedImage {
        val w = image.width
        val h = image.height
        if (w <= maxWidth && h <= maxHeight) return image
        val scale = minOf(maxWidth.toDouble() / w, maxHeight.toDouble() / h, 1.0)
        val targetW = maxOf(1, (w * scale).toInt())
        val targetH = maxOf(1, (h * scale).toInt())
        val out = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, 0, 0, targetW, targetH, null)
        g.dispose()
        return out
    }

    /** Пустой кадр заданного размера (для тестов и заглушек). */
    fun blankFrame(width: Int, height: Int, rgb: Int = 0xFF5865F2.toInt()): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(rgb)
        g.fillRect(0, 0, width, height)
        g.dispose()
        return img
    }
}
