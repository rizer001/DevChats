package devchats.desktop.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
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
import devchats.desktop.AppState
import devchats.desktop.ui.theme.DevChatsColors
import devchats.server.RoleInfo
import devchats.server.ServerInfo

/** Вкладка «Люди»: участники (заглушка) и роли. */
@Composable
fun ServerPeopleSettings(appState: AppState, server: ServerInfo) {
    var search by remember { mutableStateOf("") }
    var editingRoleId by remember { mutableStateOf<String?>(null) }
    var creatingRole by remember { mutableStateOf(false) }

    val roles = appState.rolesByServer[server.id].orEmpty()
    val searching = search.isNotBlank()
    val visible = if (searching) roles.filter { it.name.contains(search, ignoreCase = true) } else roles

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 16.dp),
    ) {
        // Участники (потом будет отдельная вкладка)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(DevChatsColors.ChannelsBg)
                .clickable { /* TODO: вкладка участников */ }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("👥 Участники", color = DevChatsColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("→", color = DevChatsColors.TextMuted, fontSize = 16.sp)
        }

        Spacer(Modifier.height(20.dp))
        SettingsSectionHeader("Роли")

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Роли", color = DevChatsColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Button(onClick = { creatingRole = true }) {
                Text("Создание роли", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Поиск роли", color = DevChatsColors.TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Роли — ${roles.size}", color = DevChatsColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Участники", color = DevChatsColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))

        if (visible.isEmpty()) {
            Text(
                text = if (searching) "Ничего не найдено" else "Ролей пока нет",
                color = DevChatsColors.TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Column(Modifier.clip(RoundedCornerShape(6.dp)).background(DevChatsColors.ChannelsBg).padding(4.dp)) {
                visible.forEachIndexed { index, role ->
                    RoleRow(
                        role = role,
                        onMoveUp = if (!searching && index > 0) {
                            { moveRole(appState, server.id, roles, index, index - 1) }
                        } else null,
                        onMoveDown = if (!searching && index < visible.size - 1) {
                            { moveRole(appState, server.id, roles, index, index + 1) }
                        } else null,
                        onEdit = { editingRoleId = role.id },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (creatingRole) {
        RoleEditorDialog(
            role = null,
            onSave = { name, color, showSeparately, mentionable ->
                appState.addRole(server.id, name)
                creatingRole = false
            },
            onDelete = null,
            onDismiss = { creatingRole = false },
        )
    }

    editingRoleId?.let { id ->
        roles.firstOrNull { it.id == id }?.let { role ->
            RoleEditorDialog(
                role = role,
                onSave = { name, color, showSeparately, mentionable ->
                    appState.updateRole(server.id, role.id, name, color, showSeparately, mentionable)
                    editingRoleId = null
                },
                onDelete = {
                    appState.deleteRole(server.id, role.id)
                    editingRoleId = null
                },
                onDismiss = { editingRoleId = null },
            )
        }
    }
}

private fun moveRole(appState: AppState, serverId: String, roles: List<RoleInfo>, from: Int, to: Int) {
    if (from !in roles.indices || to !in roles.indices) return
    val order = roles.map { it.id }.toMutableList()
    val id = order.removeAt(from)
    order.add(to, id)
    appState.reorderRoles(serverId, order)
}

@Composable
private fun RoleRow(
    role: RoleInfo,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("≡", color = DevChatsColors.TextMuted, fontSize = 15.sp)
        Spacer(Modifier.width(4.dp))
        Column {
            if (onMoveUp != null) {
                TextButton(onClick = onMoveUp, modifier = Modifier.height(20.dp)) { Text("▲", fontSize = 9.sp, color = DevChatsColors.TextMuted) }
            }
            if (onMoveDown != null) {
                TextButton(onClick = onMoveDown, modifier = Modifier.height(20.dp)) { Text("▼", fontSize = 9.sp, color = DevChatsColors.TextMuted) }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(parseHexColor(role.color)),
        )
        Spacer(Modifier.width(8.dp))
        Text(role.name, color = DevChatsColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("👤 0", color = DevChatsColors.TextMuted, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onEdit) { Text("✏️", color = DevChatsColors.TextMuted) }
    }
}

/** Редактор роли: название, цвет (16 + HEX), два переключателя, сохранить/откатить. */
@Composable
private fun RoleEditorDialog(
    role: RoleInfo?,
    onSave: (name: String, color: String, showSeparately: Boolean, mentionable: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember(role?.id) { mutableStateOf(role?.name ?: "") }
    var color by remember(role?.id) { mutableStateOf(role?.color ?: ROLE_COLORS.first()) }
    var showSeparately by remember(role?.id) { mutableStateOf(role?.showSeparately ?: false) }
    var mentionable by remember(role?.id) { mutableStateOf(role?.mentionable ?: false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, modifier = Modifier.height(32.dp)) {
                    Text("←", color = DevChatsColors.TextMuted, fontSize = 18.sp)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (role == null) "Создание роли" else "Редактировать роль ${role.name}",
                    color = DevChatsColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название роли") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Text("Цвет роли", color = DevChatsColors.TextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                ColorPicker(selected = color) { color = it }
                Spacer(Modifier.height(12.dp))
                SettingSwitchRow("Показывать участников роли отдельно", showSeparately) { showSeparately = it }
                SettingSwitchRow("Разрешить всем @упомянуть эту роль", mentionable) { mentionable = it }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, color, showSeparately, mentionable) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = DevChatsColors.Success),
            ) {
                Text("Сохранить настройки")
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Удалить", color = DevChatsColors.Danger, fontSize = 13.sp)
                    }
                }
                TextButton(onClick = { confirmReset = true }) {
                    Text("Откатить изменения", color = DevChatsColors.TextMuted, fontSize = 13.sp)
                }
            }
        },
    )

    if (confirmReset) {
        ConfirmDialog(
            title = "Откатить изменения",
            message = "Вернуть исходные настройки роли?",
            onConfirm = {
                name = role?.name ?: ""
                color = role?.color ?: ROLE_COLORS.first()
                showSeparately = role?.showSeparately ?: false
                mentionable = role?.mentionable ?: false
                confirmReset = false
            },
            onDismiss = { confirmReset = false },
        )
    }

    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "Удалить роль",
            message = "Роль «${role?.name ?: ""}» будет удалена безвозвратно.",
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
