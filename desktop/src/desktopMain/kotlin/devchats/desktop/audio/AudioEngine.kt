package devchats.desktop.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Захват и воспроизведение звука через Java Sound (PCM 48 кГц моно 16 бит).
 *
 * Работает деградированно: если микрофона или динамика нет (тесты, серверная
 * машина) — соответствующий метод возвращает null / молча пропускает,
 * а сам звонок продолжает жить.
 */
class AudioEngine {

    private val format = AudioFormat(48_000f, 16, 1, true, false) // PCM_SIGNED mono LE
    private var mic: TargetDataLine? = null
    private var speaker: SourceDataLine? = null

    /** Размер кадра PCM в байтах (960 сэмплов * 2 байта). */
    private val frameBytes: Int = 960 * 2

    /** Открывает линии ввода/вывода. Бросает исключение, если нет ни одного устройства. */
    fun open() {
        mic = runCatching {
            AudioSystem.getTargetDataLine(format).also { it.open(format); it.start() }
        }.getOrNull()
        speaker = runCatching {
            AudioSystem.getSourceDataLine(format).also { it.open(format); it.start() }
        }.getOrNull()
        if (mic == null && speaker == null) {
            throw IllegalStateException("Нет звукового устройства")
        }
    }

    /** Читает один кадр PCM с микрофона (блокирует до заполнения). null — нет микрофона. */
    fun readMicFrame(): ShortArray? {
        val line = mic ?: return null
        val bytes = ByteArray(frameBytes)
        val read = line.read(bytes, 0, bytes.size)
        if (read <= 0) return null
        val pcm = ShortArray(read / 2)
        for (i in pcm.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() shl 8
            pcm[i] = (lo or hi).toShort()
        }
        return pcm
    }

    /** Проигрывает кадр PCM. Молча пропускает, если динамика нет. */
    fun playFrame(pcm: ShortArray) {
        val line = speaker ?: return
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        line.write(bytes, 0, bytes.size)
    }

    fun close() {
        mic?.close()
        speaker?.close()
        mic = null
        speaker = null
    }
}
