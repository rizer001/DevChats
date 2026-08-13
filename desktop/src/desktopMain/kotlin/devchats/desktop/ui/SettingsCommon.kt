package devchats.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import devchats.desktop.ui.theme.DevChatsColors
import java.io.File
import javax.sound.sampled.AudioSystem

/** 16 базовых цветов для ролей и баннера (палитра в духе Discord). */
val ROLE_COLORS: List<String> = listOf(
    "#99AAB5", "#1ABC9C", "#2ECC71", "#3498DB", "#9B59B6", "#E91E63", "#F1C40F", "#E67E22",
    "#E74C3C", "#95A5A6", "#607D8B", "#11806A", "#206694", "#71368A", "#2C2F33", "#992D22",
)

/** Парсит HEX-цвет `#RRGGBB` в Compose Color; невалидный — серый. */
fun parseHexColor(hex: String): Color = runCatching {
    val clean = hex.removePrefix("#").trim()
    if (clean.length == 6) Color(0xFF000000L or clean.toLong(16)) else Color.Gray
}.getOrDefault(Color.Gray)

/** Нормализует ввод до `#RRGGBB` (только hex-цифры, максимум 6). */
fun normalizeHex(raw: String): String {
    val digits = raw.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
    return "#$digits"
}

/** Заголовок секции в настройках. */
@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = DevChatsColors.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** Строка-переключатель: подпись + Switch. */
@Composable
fun SettingSwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = DevChatsColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

/**
 * Выбор цвета: 16 базовых кружков + кнопка-карандаш для своего HEX-оттенка.
 */
@Composable
fun ColorPicker(selected: String, onSelect: (String) -> Unit) {
    var showHex by remember { mutableStateOf(false) }
    Column {
        ROLE_COLORS.chunked(8).forEach { rowColors ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 3.dp),
            ) {
                rowColors.forEach { c ->
                    val isSelected = c.equals(selected, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(parseHexColor(c))
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) Color.White else DevChatsColors.Hover,
                                shape = CircleShape,
                            )
                            .clickable { onSelect(c) },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(parseHexColor(selected)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✏️", fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showHex = !showHex }) {
                Text(
                    text = if (showHex) "Скрыть HEX" else "Свой цвет (HEX)",
                    color = DevChatsColors.Accent,
                    fontSize = 13.sp,
                )
            }
        }
        if (showHex) {
            OutlinedTextField(
                value = selected,
                onValueChange = { onSelect(normalizeHex(it)) },
                label = { Text("HEX-цвет") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Проигрывание звуков звуковой панели через Java Sound (WAV и декодируемые форматы). */
object SoundPlayer {

    /** Длительность аудиофайла в мс; 0 — формат не поддерживается. */
    fun durationMs(path: String): Long = runCatching {
        AudioSystem.getAudioInputStream(File(path)).use { ais ->
            val frames = ais.frameLength
            val rate = ais.format.frameRate
            if (frames <= 0 || rate <= 0f) 0L else (frames * 1000 / rate).toLong()
        }
    }.getOrDefault(0L)

    /** Проигрывает фрагмент [startMs]..[endMs] (0 — до конца) в фоновом потоке. */
    fun play(path: String, startMs: Long, endMs: Long) {
        Thread {
            runCatching {
                AudioSystem.getAudioInputStream(File(path)).use { ais ->
                    val format = ais.format
                    val rate = format.frameRate
                    if (rate <= 0f) return@runCatching
                    val startFrame = (startMs * rate / 1000).toLong()
                    val endFrame = if (endMs > startMs) (endMs * rate / 1000).toLong() else ais.frameLength
                    if (format.frameSize > 0) ais.skip(startFrame * format.frameSize)
                    val clip = AudioSystem.getClip()
                    clip.open(ais)
                    clip.start()
                    val playMs = (endFrame - startFrame) * 1000 / rate.toLong()
                    Thread.sleep(playMs.coerceAtLeast(0))
                    clip.stop()
                    clip.close()
                }
            }
        }.start()
    }
}
