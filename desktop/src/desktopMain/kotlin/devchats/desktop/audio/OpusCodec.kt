package devchats.desktop.audio

import io.github.jaredmdobson.OpusApplication
import io.github.jaredmdobson.OpusDecoder
import io.github.jaredmdobson.OpusEncoder

/**
 * Обёртка над чистым Java-кодеком Opus (Concentus) — без нативных библиотек.
 *
 * Один кадр: 20 мс PCM 48 кГц моно, 16 бит = 960 сэмплов. Кодируется в
 * Opus-пакет (обычно 100–400 байт при VOIP-битрейте).
 */
class OpusCodec(
    private val sampleRate: Int = 48_000,
) {
    private val encoder = OpusEncoder(sampleRate, 1, OpusApplication.OPUS_APPLICATION_VOIP)
    private val decoder = OpusDecoder(sampleRate, 1)

    /** Размер кадра PCM в сэмплах (20 мс при 48 кГц). */
    val frameSize: Int = sampleRate / 50

    private val maxPacketBytes = 4000

    /** Кодирует один кадр PCM (ровно [frameSize] сэмплов) в Opus-пакет. */
    fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == frameSize) { "ожидали $frameSize сэмплов, получили ${pcm.size}" }
        val out = ByteArray(maxPacketBytes)
        val written = encoder.encode(pcm, 0, frameSize, out, 0, out.size)
        return out.copyOf(written)
    }

    /** Декодирует Opus-пакет в PCM-кадр ([frameSize] сэмплов). */
    fun decode(packet: ByteArray): ShortArray {
        val pcm = ShortArray(frameSize)
        decoder.decode(packet, 0, packet.size, pcm, 0, frameSize, false)
        return pcm
    }
}
