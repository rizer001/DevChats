package devchats.desktop.media

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VideoCodecTest {

    private fun gradientFrame(width: Int, height: Int): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                img.setRGB(x, y, (x * 255 / width) shl 16 or (y * 255 / height))
            }
        }
        return img
    }

    @Test
    fun jpegRoundtripKeepsSize() {
        val frame = gradientFrame(320, 180)

        val jpeg = VideoCodec.encodeJpeg(frame)

        assertTrue(jpeg.isNotEmpty())
        assertTrue(jpeg.size < 100_000, "JPEG должен сжимать: ${jpeg.size} байт")

        val decoded = VideoCodec.decodeJpeg(jpeg)
        assertNotNull(decoded)
        assertEquals(320, decoded.width)
        assertEquals(180, decoded.height)
    }

    @Test
    fun scaleFitsInsideBoundsPreservingAspect() {
        val frame = gradientFrame(1920, 1080)

        val scaled = VideoCodec.scale(frame, 640, 360)

        assertEquals(640, scaled.width)
        assertEquals(360, scaled.height)
    }

    @Test
    fun smallFrameIsNotUpscaled() {
        val frame = gradientFrame(100, 50)
        val scaled = VideoCodec.scale(frame, 640, 360)
        assertEquals(100, scaled.width, "маленький кадр не должен растягиваться")
        assertEquals(50, scaled.height)
    }

    @Test
    fun corruptJpegReturnsNull() {
        assertEquals(null, VideoCodec.decodeJpeg(byteArrayOf(1, 2, 3, 4)))
    }
}
