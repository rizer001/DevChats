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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import devchats.desktop.AppState
import devchats.desktop.ui.theme.DevChatsColors

private enum class AuthMode { Login, Register }

/** Экран входа / регистрации (показывается до того, как поднят узел). */
@Composable
fun AuthScreen(appState: AppState) {
    var mode by remember { mutableStateOf(AuthMode.Login) }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var avatarPath by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier.fillMaxSize().background(DevChatsColors.ServersBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DevChatsColors.ChannelsBg)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("DevChats", color = DevChatsColors.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (mode == AuthMode.Login) "С возвращением!" else "Создайте свой узел",
                color = DevChatsColors.TextMuted,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(20.dp))

            Row {
                TabButton("Вход", mode == AuthMode.Login) { mode = AuthMode.Login }
                Spacer(Modifier.width(8.dp))
                TabButton("Регистрация", mode == AuthMode.Register) { mode = AuthMode.Register }
            }
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                label = { Text("Логин") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (mode == AuthMode.Register) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Отображаемое имя") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarCircle(avatarPath = avatarPath, name = login.ifBlank { "?" }, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { pickImageFile { path -> avatarPath = path } }) {
                        Text("Загрузить аватар", color = DevChatsColors.Accent, fontSize = 13.sp)
                    }
                }
            }

            if (appState.authError != null) {
                Spacer(Modifier.height(12.dp))
                Text(appState.authError!!, color = DevChatsColors.Danger, fontSize = 13.sp)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (mode == AuthMode.Login) {
                        appState.login(login, password)
                    } else {
                        appState.register(login, password, displayName, avatarPath)
                    }
                },
                enabled = !appState.authBusy && login.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (mode == AuthMode.Login) "Войти" else "Создать аккаунт")
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) DevChatsColors.Active else ColorTransparentBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) DevChatsColors.TextPrimary else DevChatsColors.TextMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 14.sp,
        )
    }
}

private val ColorTransparentBg: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent
