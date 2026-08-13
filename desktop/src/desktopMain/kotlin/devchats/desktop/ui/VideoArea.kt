package devchats.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import devchats.desktop.ui.theme.DevChatsColors
import devchats.protocol.VIDEO_KIND_CAMERA

/**
 * Панель видео в активном звонке: кадр собеседника на всю ширину,
 * сверху справа — маленькое локальное превью (PiP).
 */
@Composable
fun VideoArea(
    peerName: String,
    remoteKind: String?,
    remoteFrame: ImageBitmap?,
    localKind: String?,
    localFrame: ImageBitmap?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(Color.Black),
    ) {
        if (remoteFrame != null) {
            Image(
                bitmap = remoteFrame,
                contentDescription = "Видео собеседника",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = if (remoteKind == VIDEO_KIND_CAMERA) "$peerName показывает камеру…" else "$peerName показывает экран…",
                color = DevChatsColors.TextMuted,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // локальное превью (PiP)
        if (localKind != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .width(192.dp)
                    .height(108.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DevChatsColors.InputBg),
            ) {
                if (localFrame != null) {
                    Image(
                        bitmap = localFrame,
                        contentDescription = "Ваше видео",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = "включение…",
                        color = DevChatsColors.TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}
