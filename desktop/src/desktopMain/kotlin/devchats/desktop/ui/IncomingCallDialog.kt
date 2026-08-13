package devchats.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import devchats.desktop.ui.theme.DevChatsColors

/** Диалог входящего звонка: Принять / Отклонить. */
@Composable
fun IncomingCallDialog(
    peerName: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Dialog(onDismissRequest = onReject) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DevChatsColors.ServersBg)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("📞", fontSize = 40.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "Входящий звонок",
                color = DevChatsColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(peerName, color = DevChatsColors.Accent, fontSize = 15.sp)
            Spacer(Modifier.height(20.dp))
            Row {
                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(containerColor = DevChatsColors.Success),
                ) {
                    Text("Принять")
                }
                Spacer(Modifier.width(16.dp))
                OutlinedButton(onClick = onReject) {
                    Text("Отклонить", color = DevChatsColors.Danger)
                }
            }
        }
    }
}

/** Компактная полоса активного/исходящего звонка над основным интерфейсом. */
@Composable
fun CallBar(
    outgoing: Boolean,
    peerName: String,
    seconds: Int,
    muted: Boolean,
    cameraActive: Boolean,
    screenActive: Boolean,
    onMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleScreen: () -> Unit,
    onHangup: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (outgoing) DevChatsColors.InputBg else DevChatsColors.Active)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (outgoing) "📞 Звоним $peerName…" else "🔴 $peerName · ${formatDuration(seconds)}",
                color = DevChatsColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (!outgoing) {
                OutlinedButton(onClick = onMute) {
                    Text(if (muted) "🔇 Снять с беззвучия" else "🔇 Без звука", color = DevChatsColors.TextPrimary)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onToggleCamera,
                    colors = if (cameraActive) ButtonDefaults.outlinedButtonColors(contentColor = DevChatsColors.Success) else ButtonDefaults.outlinedButtonColors(),
                ) {
                    Text("📷", fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onToggleScreen,
                    colors = if (screenActive) ButtonDefaults.outlinedButtonColors(contentColor = DevChatsColors.Success) else ButtonDefaults.outlinedButtonColors(),
                ) {
                    Text("🖥️", fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = onHangup, colors = ButtonDefaults.buttonColors(containerColor = DevChatsColors.Danger)) {
                Text("Завершить")
            }
        }
    }
}

private fun formatDuration(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}
