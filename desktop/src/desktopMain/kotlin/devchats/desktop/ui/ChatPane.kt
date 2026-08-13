package devchats.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import devchats.desktop.ui.theme.DevChatsColors
import devchats.server.FileDirection
import devchats.server.FileRecord
import devchats.server.FileStatus
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File

data class ChatMessage(
    val author: String,
    val authorColor: Color,
    val text: String,
    val time: String,
    val mine: Boolean,
)

/** Элемент чата: текстовое сообщение или карточка файла. */
sealed interface ChatItem {
    data class Text(val message: ChatMessage) : ChatItem
    data class File(val record: FileRecord) : ChatItem
}

/** Основная область: шапка беседы, сообщения, файлы и поле ввода. */
@Composable
fun ChatPane(
    title: String,
    statusLine: String,
    items: List<ChatItem>,
    onSend: (String) -> Unit,
    onSendFile: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onCall: (() -> Unit)? = null,
    prefix: String = "#",
) {
    var input by remember { mutableStateOf("") }

    @OptIn(ExperimentalComposeUiApi::class)
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val transferable = when (val native = event.nativeEvent) {
                    is DropTargetDropEvent -> native.transferable
                    is DropTargetDragEvent -> native.transferable
                    else -> null
                } ?: return false
                if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @Suppress("UNCHECKED_CAST")
                    val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    files.firstOrNull()?.let { onSendFile(it.absolutePath) }
                    return true
                }
                return false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DevChatsColors.ChatBg)
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget),
    ) {
        // Шапка
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .border(1.dp, DevChatsColors.ServersBg)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(prefix, color = DevChatsColors.TextMuted)
            Spacer(Modifier.width(8.dp))
            Text(title, color = DevChatsColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Text(
                statusLine,
                color = DevChatsColors.TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onCall != null) {
                TextButton(onClick = onCall) {
                    Text("📞", fontSize = 16.sp)
                }
            }
        }

        // Сообщения и файлы
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items) { item ->
                when (item) {
                    is ChatItem.Text -> MessageRow(item.message)
                    is ChatItem.File -> FileRow(item.record, onOpenFile = onOpenFile)
                }
            }
        }

        // Поле ввода
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { pickFile("Отправить файл", imagesOnly = false, onPicked = onSendFile) }) {
                Text("📎", fontSize = 18.sp)
            }
            Spacer(Modifier.width(8.dp))
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Сообщение в $title", color = DevChatsColors.TextMuted) },
                textStyle = TextStyle(color = DevChatsColors.TextPrimary, fontSize = 14.sp),
                modifier = Modifier
                    .weight(1f)
                    // onPreviewKeyEvent перехватывает Enter ДО CoreTextField,
                    // иначе внутренний обработчик вставил бы перевод строки
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                            onSend(input.trim())
                            input = ""
                            true
                        } else {
                            false
                        }
                    },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = DevChatsColors.InputBg,
                    unfocusedContainerColor = DevChatsColors.InputBg,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                minLines = 1,
                maxLines = 5,
            )
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(message.authorColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = message.author.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.author,
                    color = message.authorColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(message.time, color = DevChatsColors.TextMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(message.text, color = DevChatsColors.TextPrimary)
        }
    }
}

@Composable
private fun FileRow(record: FileRecord, onOpenFile: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DevChatsColors.InputBg)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (record.direction == FileDirection.In) "⬇️" else "⬆️", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = record.fileName,
                color = DevChatsColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(formatSize(record.size), color = DevChatsColors.TextMuted, fontSize = 12.sp)
        }
        when (record.status) {
            FileStatus.Transferring -> {
                Spacer(Modifier.height(8.dp))
                val progress = if (record.size > 0) (record.receivedBytes.toFloat() / record.size).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${(progress * 100).toInt()}% · ${formatSize(record.receivedBytes)} из ${formatSize(record.size)}",
                    color = DevChatsColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
            FileStatus.Complete -> {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { onOpenFile(record.localPath) },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("Открыть файл", color = DevChatsColors.Accent, fontSize = 12.sp)
                }
            }
            FileStatus.Aborted -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = record.abortReason ?: "Передача прервана",
                    color = DevChatsColors.Danger,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f КБ".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f МБ".format(mb)
    return "%.2f ГБ".format(mb / 1024.0)
}

