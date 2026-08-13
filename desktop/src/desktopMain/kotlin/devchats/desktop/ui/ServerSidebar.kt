package devchats.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import devchats.desktop.ui.theme.DevChatsColors
import devchats.server.ServerInfo

/**
 * Левая колонка: мои сервера (с аватарами), внизу — личные сообщения
 * и кнопка создания сервера.
 */
@Composable
fun ServerSidebar(
    servers: List<ServerInfo>,
    selectedServerId: String?,
    onSelectServer: (String?) -> Unit,
    onCreateServer: () -> Unit,
    ownAvatarPath: String?,
    ownName: String,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(72.dp)
            .background(DevChatsColors.ServersBg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        if (servers.isEmpty()) {
            Text(
                text = "Серверов пока нет",
                color = DevChatsColors.TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        } else {
            servers.forEach { server ->
                ServerIcon(
                    server = server,
                    selected = server.id == selectedServerId,
                    onClick = { onSelectServer(server.id) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.weight(1f))

        // Личные сообщения
        DmButton(
            avatarPath = ownAvatarPath,
            name = ownName,
            selected = selectedServerId == null,
            onClick = { onSelectServer(null) },
        )
        Spacer(Modifier.height(8.dp))
        // Создание сервера
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(DevChatsColors.Hover)
                .clickable(onClick = onCreateServer),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = DevChatsColors.Online, fontSize = 24.sp)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Иконка сервера: аватар или первая буква названия. */
@Composable
private fun ServerIcon(server: ServerInfo, selected: Boolean, onClick: () -> Unit) {
    val shape = if (selected) RoundedCornerShape(16.dp) else RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(if (selected) DevChatsColors.Accent else DevChatsColors.Hover)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (server.avatarPath != null) {
            AvatarCircle(avatarPath = server.avatarPath, name = server.name, size = 48.dp)
        } else {
            Text(
                text = server.name.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Кнопка личных сообщений: свой аватар или значок. */
@Composable
private fun DmButton(avatarPath: String?, name: String, selected: Boolean, onClick: () -> Unit) {
    val shape = if (selected) RoundedCornerShape(16.dp) else CircleShape
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(if (selected) DevChatsColors.Accent else DevChatsColors.Hover)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarPath != null) {
            AvatarCircle(avatarPath = avatarPath, name = name, size = 48.dp)
        } else {
            Text("💬", fontSize = 20.sp)
        }
    }
}
