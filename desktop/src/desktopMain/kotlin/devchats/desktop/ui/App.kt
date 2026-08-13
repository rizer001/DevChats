package devchats.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import devchats.desktop.AppState
import devchats.desktop.CallPhase
import devchats.desktop.ConvItem
import devchats.desktop.NodeUiStatus
import devchats.desktop.ui.theme.DevChatsColors
import devchats.desktop.ui.theme.DevChatsTheme
import devchats.protocol.MessageInfo
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Запись беседы для списка каналов. */
data class ConvEntry(
    val section: String,
    val title: String,
    val key: String,
    val prefix: String,
    val unread: Int = 0,
)

@Composable
fun App(appState: AppState) {
    DevChatsTheme {
        if (!appState.loggedIn) {
            AuthScreen(appState)
        } else {
            MainScreen(appState)
        }
    }
}

@Composable
private fun MainScreen(appState: AppState) {
    var selectedServerId by remember { mutableStateOf<String?>(null) }
    var selectedConvKey by remember { mutableStateOf<String?>(null) }
    var showCreateServer by remember { mutableStateOf(false) }
    var serverSettingsFor by remember { mutableStateOf<String?>(null) }
    var showCreateChannel by remember { mutableStateOf(false) }
    var channelSettingsFor by remember { mutableStateOf<String?>(null) }
    var confirmDeleteServer by remember { mutableStateOf(false) }
    var confirmDeleteChannelFor by remember { mutableStateOf<String?>(null) }
    var showAddNode by remember { mutableStateOf(false) }

    val selectedServer = appState.servers.firstOrNull { it.id == selectedServerId }
    val isServerView = selectedServer != null

    val entries = if (isServerView) {
        appState.channelsByServer[selectedServer.id].orEmpty().map { channel ->
            val key = "ch:${channel.id}"
            ConvEntry("КАНАЛЫ", channel.name, key, channelKindIcon(channel.kind), appState.unread[key] ?: 0)
        }
    } else {
        buildDmEntries(appState)
    }

    // При смене представления открываем первую беседу по умолчанию.
    LaunchedEffect(selectedServerId, entries.size) {
        val valid = entries.map { it.key }
        if (valid.isNotEmpty() && selectedConvKey !in valid) {
            selectedConvKey = valid.first()
            appState.selectConversation(valid.first())
        } else if (valid.isEmpty()) {
            selectedConvKey = null
        }
    }

    val selectedConv = entries.firstOrNull { it.key == selectedConvKey } ?: entries.firstOrNull()

    val callPhase = appState.callManager.phase
    val callPeerName = appState.callManager.peerName
    val callSeconds = appState.callManager.seconds
    val callMuted = appState.callManager.muted
    val callNotice = appState.callManager.notice
    val videoKind = appState.callManager.videoKind
    val remoteVideoKind = appState.callManager.remoteVideoKind
    val showVideo = callPhase == CallPhase.Active && (videoKind != null || remoteVideoKind != null)

    Column(Modifier.fillMaxSize()) {
        if (callPhase == CallPhase.Outgoing || callPhase == CallPhase.Active) {
            CallBar(
                outgoing = callPhase == CallPhase.Outgoing,
                peerName = callPeerName,
                seconds = callSeconds,
                muted = callMuted,
                cameraActive = videoKind == "camera",
                screenActive = videoKind == "screen",
                onMute = appState.callManager::toggleMute,
                onToggleCamera = appState.callManager::toggleCamera,
                onToggleScreen = appState.callManager::toggleScreenShare,
                onHangup = appState.callManager::hangup,
            )
        }
        if (showVideo) {
            VideoArea(
                peerName = callPeerName,
                remoteKind = remoteVideoKind,
                remoteFrame = appState.callManager.remoteVideoFrame,
                localKind = videoKind,
                localFrame = appState.callManager.localVideoFrame,
            )
        }
        Row(Modifier.fillMaxSize()) {
            ServerSidebar(
                servers = appState.servers,
                selectedServerId = selectedServerId,
                onSelectServer = { id -> selectedServerId = id },
                onCreateServer = { showCreateServer = true },
                ownAvatarPath = appState.nodeAvatarPath,
                ownName = appState.nodeName,
            )
            ChannelList(
                headerTitle = if (isServerView) selectedServer.name else "Личные сообщения",
                headerSubtitle = selectedServer?.description.orEmpty(),
                isServerView = isServerView,
                entries = entries,
                selectedConvKey = selectedConv?.key,
                onSelectConversation = { key ->
                    selectedConvKey = key
                    appState.selectConversation(key)
                },
                onServerSettings = if (isServerView) ({ serverSettingsFor = selectedServer.id }) else null,
                onCreateChannel = if (isServerView) ({ showCreateChannel = true }) else null,
                onConnectNode = if (!isServerView) ({ showAddNode = true }) else null,
                onRenameChannel = if (isServerView) ({ conv -> channelSettingsFor = conv.key.removePrefix("ch:") }) else null,
                onChannelSettings = if (isServerView) ({ conv -> channelSettingsFor = conv.key.removePrefix("ch:") }) else null,
                onDeleteChannel = if (isServerView) ({ conv -> confirmDeleteChannelFor = conv.key.removePrefix("ch:") }) else null,
            )
            ChatPane(
                prefix = selectedConv?.prefix ?: "#",
                title = selectedConv?.title ?: if (isServerView) "—" else "Личные сообщения",
                statusLine = statusLineFor(selectedConv, isServerView, selectedServer?.description.orEmpty(), appState),
                items = selectedConv?.let { conv ->
                    appState.messages[conv.key].orEmpty().map { item ->
                        when (item) {
                            is ConvItem.Text -> ChatItem.Text(toChatMessage(item.info, appState))
                            is ConvItem.File -> ChatItem.File(item.record)
                        }
                    }
                }.orEmpty(),
                onSend = { text -> selectedConv?.let { appState.sendMessage(it.key, text) } },
                onSendFile = { path -> selectedConv?.let { appState.sendFile(it.key, path) } },
                onOpenFile = ::openFileWithDesktop,
                onCall = selectedConv?.takeIf { it.key.startsWith("dm:") && appState.isCallableConversation(it.key) }
                    ?.let { { appState.startCall(it.key) } },
            )
        }
    }

    if (callPhase == CallPhase.Incoming) {
        IncomingCallDialog(
            peerName = callPeerName,
            onAccept = appState.callManager::acceptIncoming,
            onReject = appState.callManager::rejectIncoming,
        )
    }

    if (callNotice != null) {
        CallToast(message = callNotice, onDismiss = appState.callManager::clearNotice)
    }

    if (showCreateServer) {
        CreateServerDialog(
            onCreate = { name, description, avatar ->
                appState.createServer(name, description, avatar)
                showCreateServer = false
            },
            onDismiss = { showCreateServer = false },
        )
    }

    serverSettingsFor?.let { id ->
        ServerSettingsDialog(
            appState = appState,
            serverId = id,
            onDeleteServer = {
                serverSettingsFor = null
                confirmDeleteServer = true
            },
            onDismiss = { serverSettingsFor = null },
        )
    }

    if (showCreateChannel) {
        CreateChannelDialog(
            onCreate = { name, kind, description ->
                selectedServerId?.let { appState.createChannel(it, name, kind, description) }
                showCreateChannel = false
            },
            onDismiss = { showCreateChannel = false },
        )
    }

    channelSettingsFor?.let { channelId ->
        appState.channelById(channelId)?.let { channel ->
            ChannelSettingsDialog(
                channel = channel,
                onSave = { name, kind, description ->
                    appState.updateChannel(channelId, name, kind, description)
                    channelSettingsFor = null
                },
                onDelete = {
                    channelSettingsFor = null
                    confirmDeleteChannelFor = channelId
                },
                onDismiss = { channelSettingsFor = null },
            )
        }
    }

    confirmDeleteChannelFor?.let { channelId ->
        ConfirmDialog(
            title = "Удалить канал",
            message = "Канал и все его сообщения будут удалены безвозвратно.",
            onConfirm = {
                appState.deleteChannel(channelId)
                if (selectedConvKey == "ch:$channelId") selectedConvKey = null
                confirmDeleteChannelFor = null
            },
            onDismiss = { confirmDeleteChannelFor = null },
        )
    }

    if (confirmDeleteServer) {
        ConfirmDialog(
            title = "Удалить сервер",
            message = "Сервер «${selectedServer?.name ?: ""}» и все его каналы будут удалены безвозвратно.",
            onConfirm = {
                selectedServerId?.let { appState.deleteServer(it) }
                selectedServerId = null
                selectedConvKey = null
                confirmDeleteServer = false
            },
            onDismiss = { confirmDeleteServer = false },
        )
    }

    if (showAddNode) {
        AddServerDialog(
            onConnect = { host, port ->
                appState.addServer(host, port)
                showAddNode = false
            },
            onDismiss = { showAddNode = false },
        )
    }

    if (appState.pendingRequests.isNotEmpty()) {
        PendingRequestsDialog(
            requests = appState.pendingRequests,
            onAccept = appState::accept,
            onDeny = appState::deny,
        )
    }
}

/** Тост с уведомлением о звонке («Собеседник занят» и т.п.), исчезает сам. */
@Composable
private fun CallToast(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        delay(4000)
        onDismiss()
    }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(DevChatsColors.InputBg)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(message, color = DevChatsColors.TextPrimary, fontSize = 14.sp)
        }
    }
}

/** Беседы DM-вида: личные сообщения + каналы подключённых узлов. */
private fun buildDmEntries(appState: AppState): List<ConvEntry> {
    val result = mutableListOf<ConvEntry>()
    appState.dmPeers.forEach { peer ->
        val key = "dm:${peer.nodeId}"
        result.add(ConvEntry("СООБЩЕНИЯ", peer.displayName, key, "@", appState.unread[key] ?: 0))
    }
    appState.nodes.forEach { node ->
        val channels = appState.remoteChannels[node.key].orEmpty()
        if (channels.isEmpty()) return@forEach
        channels.forEach { channel ->
            val key = "ch:${channel.id}@${node.key}"
            result.add(ConvEntry("КАНАЛЫ УЗЛА · ${node.name}", channel.name, key, channelKindIcon(channel.kind), appState.unread[key] ?: 0))
        }
    }
    return result
}

private fun statusLineFor(
    conv: ConvEntry?,
    isServerView: Boolean,
    serverDescription: String,
    appState: AppState,
): String = when {
    isServerView -> {
        val channelId = conv?.key?.takeIf { it.startsWith("ch:") }?.removePrefix("ch:")
        val channel = channelId?.let { appState.channelById(it) }
        val kindLabel = channel?.let { channelKindLabel(it.kind) } ?: "канал"
        val desc = channel?.description?.takeIf { it.isNotBlank() } ?: serverDescription.takeIf { it.isNotBlank() }
        if (desc != null) "$kindLabel · $desc" else "$kindLabel · локальный узел · порт ${appState.boundPort}"
    }
    conv?.key?.startsWith("dm:") == true -> "личное сообщение · ${nodeStatusLine(appState)}"
    else -> "локальный узел · порт ${appState.boundPort}"
}

private fun nodeStatusLine(appState: AppState): String {
    val status = appState.serverStatus
    return when (status) {
        devchats.server.ServerStatus.Running -> "узел работает · порт ${appState.boundPort}"
        devchats.server.ServerStatus.Starting -> "узел запускается…"
        devchats.server.ServerStatus.Stopped -> "узел остановлен"
        is devchats.server.ServerStatus.Failed -> "ошибка узла: ${status.reason}"
    }
}

private fun toChatMessage(info: MessageInfo, appState: AppState): ChatMessage {
    val mine = info.authorNodeId == appState.nodeId
    val name = when {
        mine -> appState.nodeName
        info.channelId != null -> appState.dmPeers.firstOrNull { it.nodeId == info.authorNodeId }?.displayName
            ?: info.authorNodeId.take(8)
        else -> appState.dmPeers.firstOrNull { it.nodeId == info.authorNodeId }?.displayName
            ?: info.authorNodeId.take(8)
    }
    return ChatMessage(
        author = name,
        authorColor = colorFor(info.authorNodeId),
        text = info.text,
        time = formatTime(info.timestamp),
        mine = mine,
    )
}

private val authorColors = listOf(
    Color(0xFF5865F2),
    Color(0xFFFAA61A),
    Color(0xFFEB459E),
    Color(0xFF00A8FC),
    Color(0xFF23A55A),
    Color(0xFFED4245),
)

private fun colorFor(nodeId: String): Color =
    authorColors[(nodeId.hashCode() and Int.MAX_VALUE) % authorColors.size]

private fun formatTime(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(timeFormatter)

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun openFileWithDesktop(path: String) {
    runCatching { java.awt.Desktop.getDesktop().open(java.io.File(path)) }
}
