package devchats.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import devchats.desktop.ui.theme.DevChatsColors
import devchats.protocol.ChannelInfo

/**
 * Настройки канала: переименовать, сменить тип, изменить описание,
 * удалить канал (с подтверждением).
 */
@Composable
fun ChannelSettingsDialog(
    channel: ChannelInfo,
    onSave: (name: String, kind: String, description: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(channel.name) }
    var kind by remember { mutableStateOf(channel.kind) }
    var description by remember { mutableStateOf(channel.description) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки канала") },
        text = {
            Column {
                ChannelKindSelector(selected = kind, onSelect = { kind = it })
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название канала") },
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
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Удалить канал", color = DevChatsColors.Danger)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, kind, description) },
                enabled = name.isNotBlank(),
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
