package devchats.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Палитра в духе Discord. */
object DevChatsColors {
    val ServersBg = Color(0xFF1E1F22)
    val ChannelsBg = Color(0xFF2B2D31)
    val ChatBg = Color(0xFF313338)
    val InputBg = Color(0xFF383A40)
    val Hover = Color(0xFF35373C)
    val Active = Color(0xFF404249)
    val Accent = Color(0xFF5865F2)
    val TextPrimary = Color(0xFFDBDEE1)
    val TextMuted = Color(0xFF949BA4)
    val Danger = Color(0xFFED4245)
    val Online = Color(0xFF23A55A)
    val Success = Color(0xFF23A55A)
}

@Composable
fun DevChatsTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = DevChatsColors.Accent,
        background = DevChatsColors.ChatBg,
        surface = DevChatsColors.ChannelsBg,
    )
    MaterialTheme(colorScheme = colors, content = content)
}
