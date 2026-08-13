package devchats.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import devchats.desktop.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() {
    // На некоторых машинах GPU-рендеринг Skiko (OpenGL/ANGLE) падает нативным
    // крашем (0xC000041D) при старте. По умолчанию используем стабильный
    // программный рендеринг; аппаратный можно включить через
    // SKIKO_RENDER_API=DIRECT3D (или skiko.renderApi).
    if (System.getProperty("skiko.renderApi") == null && System.getenv("SKIKO_RENDER_API").isNullOrBlank()) {
        System.setProperty("skiko.renderApi", "SOFTWARE")
    }

    // До входа в аккаунт сервер узла не поднимается — показываем экран входа.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val appState = AppState(scope)
    appState.init()

    // Маркер сборки и путь к данным — при запуске из IDE сразу видно, что
    // выполняется актуальная версия кода и куда пишется БД (SQLite).
    println("[DevChats] сборка: аккаунты + сервера + каналы + настройки сервера (M7+)")
    println("[DevChats] данные хранятся в: " + devchats.server.NodeStore.defaultDataDir().toAbsolutePath())
    println("[DevChats] если данные «пропадают» — проверь путь выше и DEVCHATS_HOME/DEVCHATS_HOME в конфигурации запуска")

    application {
        Window(
            onCloseRequest = {
                appState.stop()
                exitApplication()
            },
            title = "DevChats [R4]",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            App(appState)
        }
    }
}
