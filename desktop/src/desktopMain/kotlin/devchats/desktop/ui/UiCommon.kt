package devchats.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import devchats.desktop.ui.theme.DevChatsColors
import devchats.protocol.ALL_CHANNEL_KINDS
import devchats.protocol.CHANNEL_KIND_ANNOUNCEMENTS
import devchats.protocol.CHANNEL_KIND_CONFERENCE
import devchats.protocol.CHANNEL_KIND_FORUM
import devchats.protocol.CHANNEL_KIND_VOICE
import java.awt.EventQueue
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Диагностический лог: пишет строку в консоль И в файл <dataDir>/devchats-debug.log.
 * Файл — рядом с БД, чтобы при проблемах с ПКМ/рендером можно было посмотреть,
 * доходят ли события вообще (консоль в IDE могут не смотреть).
 */
fun debugLog(msg: String) {
    val line = "[${java.time.LocalTime.now().toString().take(8)}] $msg"
    println(line)
    runCatching {
        val dir = devchats.server.NodeStore.defaultDataDir().toFile()
        dir.mkdirs()
        File(dir, "devchats-debug.log").appendText(line + "\n")
    }
}

/**
 * Обработчик правой кнопки мыши (ПКМ) поверх стабильного pointerInput-API.
 *
 * Слушает в [PointerEventPass.Initial], чтобы перехватить нажатие ДО того, как
 * внутренние элементы (например, [androidx.compose.foundation.clickable])
 * потребят событие. [block] получает позицию клика относительно этого модификатора.
 * Событие потребляется, чтобы родитель (пустое место списка) не открыл своё меню.
 *
 * Проверяем И [event.button] (кнопка, которая изменилась), И маску [event.buttons]:
 * на Windows в некоторых версиях Compose маска может быть пустой, а [event.button]
 * заполнен — и наоборот.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.onSecondaryClick(block: (Offset) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
            val isSecondary = event.buttons.isSecondaryPressed || event.button == PointerButton.Secondary
            if (isSecondary) {
                change.consume()
                block(change.position)
            }
        }
    }
}

/** Декодирует картинку аватара из файла; null — файл не читается/не картинка. */
fun loadAvatarBitmap(path: String?): ImageBitmap? = path?.let {
    runCatching {
        val bytes = File(it).readBytes()
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

/** Круглый аватар: картинка или первая буква имени. */
@Composable
fun AvatarCircle(
    avatarPath: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(avatarPath) { loadAvatarBitmap(avatarPath) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(DevChatsColors.Hover),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Иконка канала по типу. */
fun channelKindIcon(kind: String): String = when (kind) {
    CHANNEL_KIND_VOICE -> "🔊"
    CHANNEL_KIND_ANNOUNCEMENTS -> "📢"
    CHANNEL_KIND_CONFERENCE -> "🎥"
    CHANNEL_KIND_FORUM -> "💬"
    else -> "#"
}

/** Человеческое название типа канала. */
fun channelKindLabel(kind: String): String = when (kind) {
    CHANNEL_KIND_VOICE -> "Голос"
    CHANNEL_KIND_ANNOUNCEMENTS -> "Объявления"
    CHANNEL_KIND_CONFERENCE -> "Конференция"
    CHANNEL_KIND_FORUM -> "Форум"
    else -> "Текст"
}

/** Выбор типа канала (в диалогах создания/настройки). */
@Composable
fun ChannelKindSelector(selected: String, onSelect: (String) -> Unit) {
    Column {
        ALL_CHANNEL_KINDS.forEach { kind ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (kind == selected) DevChatsColors.Active else Color.Transparent)
                    .clickable { onSelect(kind) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${channelKindIcon(kind)}  ${channelKindLabel(kind)}",
                    color = if (kind == selected) DevChatsColors.TextPrimary else DevChatsColors.TextMuted,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/**
 * Диалог выбора файла-картинки (не блокирует UI).
 *
 * Используем Swing [JFileChooser] вместо java.awt.FileDialog: нативный FileDialog
 * на Windows в новых JDK (24/25) валит JVM крашем 0xC000041D. JFileChooser —
 * чистый Java, без нативного кода диалога. Сам диалог крутим на Event Dispatch
 * Thread через [EventQueue.invokeAndWait], а результат отдаём с фонового потока.
 */
fun pickImageFile(onPicked: (String) -> Unit) {
    pickFile("Выберите изображение", imagesOnly = true, onPicked = onPicked)
}

/** Диалог выбора файла через Swing JFileChooser (не блокирует UI). */
fun pickFile(title: String, imagesOnly: Boolean, onPicked: (String) -> Unit) {
    Thread {
        try {
            var picked: String? = null
            EventQueue.invokeAndWait {
                // Нативный вид диалога (Windows/Linux/macOS), а не Metal-стиль Swing.
                runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
                val chooser = JFileChooser()
                chooser.dialogTitle = title
                if (imagesOnly) {
                    chooser.fileFilter = FileNameExtensionFilter(
                        "Изображения (*.png, *.jpg, *.jpeg, *.gif, *.bmp, *.webp)",
                        "png", "jpg", "jpeg", "gif", "bmp", "webp",
                    )
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    picked = chooser.selectedFile?.absolutePath
                }
            }
            picked?.let(onPicked)
        } catch (_: Exception) {
            // диалог не открылся — пропускаем
        }
    }.start()
}
