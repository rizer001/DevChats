package devchats.desktop.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpusCodecTest {

    private fun sineFrame(size: Int, freq: Double = 440.0, amp: Int = 8000): ShortArray =
        ShortArray(size) { (sin(2 * PI * freq * it / 48_000.0) * amp).toInt().toShort() }

    @Test
    fun encodeProducesCompressedPacket() {
        val codec = OpusCodec()
        val frame = sineFrame(codec.frameSize)

        val packet = codec.encode(frame)

        assertTrue(packet.isNotEmpty(), "Opus-пакет не должен быть пустым")
        assertTrue(packet.size < frame.size * 2, "пакет должен быть меньше сырого PCM (${packet.size} vs ${frame.size * 2})")
    }

    @Test
    fun decodeRestoresSignal() {
        val codec = OpusCodec()
        val frame = sineFrame(codec.frameSize)

        val decoded = codec.decode(codec.encode(frame))

        assertEquals(codec.frameSize, decoded.size, "кадр должен вернуться той же длины")
        val energy = decoded.sumOf { it.toLong() * it }
        assertTrue(energy > 0, "декодированный сигнал пуст")
    }

    @Test
    fun silenceIsPreserved() {
        val codec = OpusCodec()
        val silent = ShortArray(codec.frameSize)

        val decoded = codec.decode(codec.encode(silent))

        val maxAmp = decoded.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        assertTrue(maxAmp <= 200, "тишина должна остаться тишиной, maxAmp=$maxAmp")
    }
}
