package devchats.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import devchats.desktop.AppState
import devchats.desktop.ui.theme.DevChatsColors
import devchats.server.ServerInfo
import devchats.server.SoundInfo

/** Вкладка «Основное»: профиль сервера, другие настройки, реакции. */
@Composable
fun ServerOverviewSettings(appState: AppState, server: ServerInfo) {
    var showEmojiDialog by remember { mutableStateOf(false) }
    var soundDialogFor by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 8.dp),
    ) {
        ProfileSection(appState, server)
        SettingsDivider()
        OtherSettingsSection(appState, server)
        SettingsDivider()
        ReactionsSection(
            appState = appState,
            server = server,
            onAddEmoji = { showEmojiDialog = true },
            onAddSound = { soundDialogFor = "" },
            onEditSound = { soundDialogFor = it.id },
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showEmojiDialog) {
        EmojiUploadDialog(
            onAdd = { name, path ->
                appState.addEmoji(server.id, name, path)
                showEmojiDialog = false
            },
            onDismiss = { showEmojiDialog = false },
        )
    }

    soundDialogFor?.let { editingId ->
        val sound = if (editingId.isNotEmpty()) appState.soundsByServer[server.id].orEmpty().firstOrNull { it.id == editingId } else null
        SoundUploadDialog(
            sound = sound,
            onSave = { name, sourcePath, keyBind, trimStartMs, trimEndMs ->
                if (sound == null) {
                    sourcePath?.let { appState.addSound(server.id, name, it, keyBind) }
                } else {
                    appState.updateSound(server.id, sound.id, keyBind, trimStartMs, trimEndMs)
                }
                soundDialogFor = null
            },
            onDismiss = { soundDialogFor = null },
        )
    }
}

@Composable
private fun ProfileSection(appState: AppState, server: ServerInfo) {
    var name by remember(server.id) { mutableStateOf(server.name) }
    var description by remember(server.id) { mutableStateOf(server.description) }

    SettingsSectionHeader("Профиль сервера")

    Text("Цвет баннера сервера", color = DevChatsColors.TextMuted, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    ColorPicker(selected = server.bannerColor) { color ->
        appState.updateConfig(server) { it.copy(bannerColor = color) }
    }

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarCircle(avatarPath = server.avatarPath, name = name.ifBlank { "С" }, size = 56.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            TextButton(
                onClick = { pickImageFile { path -> appState.setServerAvatar(server.id, path) } },
            ) {
                Text("Сменить аватар", color = DevChatsColors.Accent, fontSize = 13.sp)
            }
            Text("Рекомендуется квадратная картинка", color = DevChatsColors.TextMuted, fontSize = 11.sp)
        }
    }

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Название сервера") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = description,
        onValueChange = { description = it },
        label = { Text("Описание") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { appState.updateServer(server.id, name, description) },
        enabled = name.isNotBlank(),
    ) {
        Text("Сохранить профиль")
    }
}

@Composable
private fun OtherSettingsSection(appState: AppState, server: ServerInfo) {
    SettingsSectionHeader("Другие настройки")

    SettingSwitchRow("Сообщение о входе/выходе пользователей", server.joinLeaveEnabled) { v ->
        appState.updateConfig(server) { it.copy(joinLeaveEnabled = v) }
    }

    if (server.joinLeaveEnabled) {
        Text("Канал(ы) этих сообщений:", color = DevChatsColors.TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        JoinLeaveChannelsEditor(appState, server)
    }

    Spacer(Modifier.height(12.dp))
    SettingSwitchRow("Канал бездействия", server.afkEnabled) { v ->
        appState.updateConfig(server) { it.copy(afkEnabled = v) }
    }
    if (server.afkEnabled) {
        AfkChannelSelector(appState, server)
    }
}

@Composable
private fun JoinLeaveChannelsEditor(appState: AppState, server: ServerInfo) {
    val channels = appState.channelsByServer[server.id].orEmpty()
    var pickerOpen by remember { mutableStateOf(false) }

    Column {
        if (server.joinLeaveChannelIds.isEmpty()) {
            Text("— нет каналов —", color = DevChatsColors.TextMuted, fontSize = 13.sp)
        } else {
            server.joinLeaveChannelIds.forEach { channelId ->
                val channel = channels.firstOrNull { it.id == channelId }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${channelKindIcon(channel?.kind ?: "text")} ${channel?.name ?: channelId}",
                        color = DevChatsColors.TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            appState.updateConfig(server) { it.copy(joinLeaveChannelIds = it.joinLeaveChannelIds - channelId) }
                        },
                    ) {
                        Text("Удалить", color = DevChatsColors.Danger, fontSize = 12.sp)
                    }
                }
            }
        }

        Box {
            TextButton(onClick = { pickerOpen = true }) {
                Text("＋ Добавить канал", color = DevChatsColors.Accent, fontSize = 13.sp)
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                val available = channels.filter { it.id !in server.joinLeaveChannelIds }
                if (available.isEmpty()) {
                    DropdownMenuItem(text = { Text("Нет доступных каналов") }, onClick = { pickerOpen = false })
                } else {
                    available.forEach { channel ->
                        DropdownMenuItem(
                            text = { Text("${channelKindIcon(channel.kind)} ${channel.name}") },
                            onClick = {
                                pickerOpen = false
                                appState.updateConfig(server) { it.copy(joinLeaveChannelIds = it.joinLeaveChannelIds + channel.id) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AfkChannelSelector(appState: AppState, server: ServerInfo) {
    val channels = appState.channelsByServer[server.id].orEmpty()
    var pickerOpen by remember { mutableStateOf(false) }
    val selected = channels.firstOrNull { it.id == server.afkChannelId }

    Column {
        Text("Выберите канал:", color = DevChatsColors.TextMuted, fontSize = 13.sp)
        Box {
            TextButton(onClick = { pickerOpen = true }) {
                Text(
                    text = selected?.let { "${channelKindIcon(it.kind)} ${it.name}" } ?: "— выбрать канал —",
                    color = DevChatsColors.TextPrimary,
                    fontSize = 13.sp,
                )
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Без канала") },
                    onClick = {
                        pickerOpen = false
                        appState.updateConfig(server) { it.copy(afkChannelId = null) }
                    },
                )
                channels.forEach { channel ->
                    DropdownMenuItem(
                        text = { Text("${channelKindIcon(channel.kind)} ${channel.name}") },
                        onClick = {
                            pickerOpen = false
                            appState.updateConfig(server) { it.copy(afkChannelId = channel.id) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReactionsSection(
    appState: AppState,
    server: ServerInfo,
    onAddEmoji: () -> Unit,
    onAddSound: () -> Unit,
    onEditSound: (SoundInfo) -> Unit,
) {
    val emojis = appState.emojisByServer[server.id].orEmpty()
    val sounds = appState.soundsByServer[server.id].orEmpty()

    SettingsSectionHeader("Реакции")

    Text("Эмодзи", color = DevChatsColors.TextPrimary, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    SettingSwitchRow("Автодополнение", server.emojiAutocomplete) { v ->
        appState.updateConfig(server) { it.copy(emojiAutocomplete = v) }
    }
    SettingSwitchRow("Превращение :имя: в эмодзи в сообщениях", server.emojiConvert) { v ->
        appState.updateConfig(server) { it.copy(emojiConvert = v) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onAddEmoji) { Text("Загрузить эмодзи") }
        Spacer(Modifier.width(12.dp))
        Text("${emojis.size} шт.", color = DevChatsColors.TextMuted, fontSize = 13.sp)
    }

    if (emojis.isEmpty()) {
        Text("Эмодзи пока нет", color = DevChatsColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
    } else {
        Spacer(Modifier.height(6.dp))
        Column(Modifier.clip(RoundedCornerShape(6.dp)).background(DevChatsColors.ChannelsBg).padding(8.dp)) {
            emojis.forEach { emoji ->
                EmojiRow(
                    emoji = emoji,
                    onDelete = { appState.deleteEmoji(server.id, emoji.id) },
                )
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Text("Звуковая панель", color = DevChatsColors.TextPrimary, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onAddSound) { Text("Загрузить звук") }
        Spacer(Modifier.width(12.dp))
        Text("${sounds.size} шт.", color = DevChatsColors.TextMuted, fontSize = 13.sp)
    }
    if (sounds.isEmpty()) {
        Text("Звуков пока нет", color = DevChatsColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
    } else {
        Spacer(Modifier.height(6.dp))
        Column(Modifier.clip(RoundedCornerShape(6.dp)).background(DevChatsColors.ChannelsBg).padding(8.dp)) {
            sounds.forEach { sound ->
                SoundRow(
                    sound = sound,
                    onPlay = { SoundPlayer.play(sound.filePath, sound.trimStartMs, sound.trimEndMs) },
                    onEdit = { onEditSound(sound) },
                    onDelete = { appState.deleteSound(server.id, sound.id) },
                )
            }
        }
    }
}

@Composable
private fun EmojiRow(emoji: devchats.server.EmojiInfo, onDelete: () -> Unit) {
    val bitmap = remember(emoji.imagePath) { loadAvatarBitmap(emoji.imagePath) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(DevChatsColors.Hover),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = emoji.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp))
            } else {
                Text("🖼️", fontSize = 14.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(":${emoji.name}:", color = DevChatsColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDelete) {
            Text("Удалить", color = DevChatsColors.Danger, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SoundRow(sound: SoundInfo, onPlay: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔊", fontSize = 15.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(sound.name, color = DevChatsColors.TextPrimary, fontSize = 14.sp)
            if (sound.keyBind.isNotBlank()) {
                Text("Кнопка: ${sound.keyBind}", color = DevChatsColors.TextMuted, fontSize = 11.sp)
            }
        }
        TextButton(onClick = onPlay) { Text("▶", color = DevChatsColors.Accent) }
        TextButton(onClick = onEdit) { Text("✏️", color = DevChatsColors.TextMuted) }
        TextButton(onClick = onDelete) { Text("🗑", color = DevChatsColors.Danger) }
    }
}

@Composable
private fun EmojiUploadDialog(onAdd: (name: String, sourcePath: String) -> Unit, onDismiss: () -> Unit) {
    var imagePath by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    val bitmap = remember(imagePath) { loadAvatarBitmap(imagePath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Загрузить эмодзи") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(DevChatsColors.Hover),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitmap != null) {
                            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                        } else {
                            Text("картинка", color = DevChatsColors.TextMuted, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { pickImageFile { imagePath = it } }) {
                        Text("Загрузить эмодзи", color = DevChatsColors.Accent, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Будет выглядеть как :${name.ifBlank { "имя" }}:",
                    color = DevChatsColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, imagePath ?: return@Button) },
                enabled = name.isNotBlank() && imagePath != null,
            ) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun SoundUploadDialog(
    sound: SoundInfo?,
    onSave: (name: String, sourcePath: String?, keyBind: String, trimStartMs: Long, trimEndMs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var sourcePath by remember(sound?.id) { mutableStateOf(sound?.filePath) }
    var name by remember(sound?.id) { mutableStateOf(sound?.name ?: "") }
    var keyBind by remember(sound?.id) { mutableStateOf(sound?.keyBind ?: "") }
    val duration = remember(sourcePath) { if (sourcePath != null) SoundPlayer.durationMs(sourcePath!!) else 0L }
    var trimStart by remember(sound?.id) { mutableStateOf((sound?.trimStartMs ?: 0L).toFloat()) }
    var trimEnd by remember(sound?.id) { mutableStateOf((sound?.trimEndMs ?: 0L).toFloat()) }
    val maxMs = duration.coerceAtLeast(1L).toFloat()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (sound == null) "Загрузить звук" else "Настроить звук") },
        text = {
            Column {
                if (sound == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { pickFile("Выберите звуковой файл", imagesOnly = false) { sourcePath = it } }) {
                            Text("Загрузить файл звука", color = DevChatsColors.Accent, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = sourcePath?.substringAfterLast('/')?.substringAfterLast('\\') ?: "файл не выбран",
                            color = DevChatsColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(sound.name, color = DevChatsColors.TextPrimary, fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyBind,
                    onValueChange = { keyBind = it.take(8) },
                    label = { Text("Кнопка активации (клавиша)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (sourcePath != null && duration > 0L) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { SoundPlayer.play(sourcePath!!, trimStart.toLong(), trimEnd.toLong()) }) {
                            Text("▶ Послушать", color = DevChatsColors.Accent, fontSize = 13.sp)
                        }
                        Text(
                            text = "Обрезка: ${formatMs(trimStart.toLong())}–${formatMs(trimEnd.toLong())}",
                            color = DevChatsColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Text("Начало", color = DevChatsColors.TextMuted, fontSize = 12.sp)
                    Slider(value = trimStart, onValueChange = { trimStart = it.coerceAtMost(trimEnd) }, valueRange = 0f..maxMs)
                    Text("Конец", color = DevChatsColors.TextMuted, fontSize = 12.sp)
                    Slider(value = trimEnd, onValueChange = { trimEnd = it.coerceIn(trimStart, maxMs) }, valueRange = 0f..maxMs)
                } else if (sourcePath != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Формат не поддерживается Java Sound (нужен WAV)", color = DevChatsColors.Danger, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val start = trimStart.toLong()
                    val end = if (trimEnd > trimStart) trimEnd.toLong() else 0L
                    onSave(name, if (sound == null) sourcePath else null, keyBind, start, end)
                },
                enabled = sound != null || (name.isNotBlank() && sourcePath != null),
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

private fun formatMs(ms: Long): String {
    val sec = ms / 1000
    return "%d:%02d".format(sec / 60, sec % 60)
}

@Composable
private fun SettingsDivider() {
    Spacer(Modifier.height(16.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DevChatsColors.ChannelsBg),
    )
    Spacer(Modifier.height(16.dp))
}
