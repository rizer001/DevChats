package devchats.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import devchats.server.Peer

/** Входящие запросы на подключение: принять или отклонить. */
@Composable
fun PendingRequestsDialog(
    requests: List<Peer>,
    onAccept: (String) -> Unit,
    onDeny: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Запросы на подключение") },
        text = {
            Column {
                requests.forEach { peer ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = "${peer.displayName} (${peer.address ?: "?"})",
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onAccept(peer.nodeId) }) { Text("Принять") }
                        TextButton(onClick = { onDeny(peer.nodeId) }) { Text("Отклонить") }
                    }
                }
            }
        },
        confirmButton = {},
    )
}
