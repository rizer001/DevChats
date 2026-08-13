package devchats.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import devchats.desktop.AppState
import devchats.desktop.ui.theme.DevChatsColors
import devchats.server.ServerInfo

/**
 * Окно настроек сервера: слева колонка «Настройки сервера» с вкладками
 * (Основное / Люди), справа содержимое выбранной вкладки.
 */
@Composable
fun ServerSettingsDialog(
    appState: AppState,
    serverId: String,
    onDeleteServer: () -> Unit,
    onDismiss: () -> Unit,
) {
    val server = appState.servers.firstOrNull { it.id == serverId } ?: return
    var tab by remember { mutableStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.size(width = 1000.dp, height = 660.dp),
            shape = RoundedCornerShape(12.dp),
            color = DevChatsColors.ChatBg,
        ) {
            Row(Modifier.fillMaxSize()) {
                // Левая колонка: настройки сервера + вкладки
                Column(
                    modifier = Modifier
                        .width(240.dp)
                        .fillMaxHeight()
                        .background(DevChatsColors.ServersBg)
                        .padding(vertical = 16.dp),
                ) {
                    Text(
                        text = "НАСТРОЙКИ СЕРВЕРА",
                        color = DevChatsColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarCircle(avatarPath = server.avatarPath, name = server.name, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = server.name,
                            color = DevChatsColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    SettingsNavTab("Основное", selected = tab == 0) { tab = 0 }
                    SettingsNavTab("Люди", selected = tab == 1) { tab = 1 }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onDeleteServer,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text("Удалить сервер", color = DevChatsColors.Danger, fontSize = 13.sp)
                    }
                }

                // Правая часть: заголовок вкладки + содержимое
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (tab == 0) "Основное" else "Люди",
                            color = DevChatsColors.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismiss) {
                            Text("✕", color = DevChatsColors.TextMuted, fontSize = 16.sp)
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(DevChatsColors.ChannelsBg),
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            0 -> ServerOverviewSettings(appState, server)
                            else -> ServerPeopleSettings(appState, server)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) DevChatsColors.Active else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) DevChatsColors.TextPrimary else DevChatsColors.TextMuted,
            fontSize = 15.sp,
        )
    }
}

/** Обновляет настройки сервера из копии, меняя одно или несколько полей. */
internal fun AppState.updateConfig(server: ServerInfo, transform: (ServerInfo) -> ServerInfo) {
    val updated = transform(server)
    updateServerConfig(
        id = server.id,
        bannerColor = updated.bannerColor,
        joinLeaveEnabled = updated.joinLeaveEnabled,
        joinLeaveChannelIds = updated.joinLeaveChannelIds,
        afkEnabled = updated.afkEnabled,
        afkChannelId = updated.afkChannelId,
        emojiAutocomplete = updated.emojiAutocomplete,
        emojiConvert = updated.emojiConvert,
    )
}
