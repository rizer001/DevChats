package devchats.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import devchats.client.NodeClient
import devchats.client.NodeConnectionState
import devchats.client.RemoteNode
import devchats.protocol.CallAccept
import devchats.protocol.CallAudio
import devchats.protocol.CallHangup
import devchats.protocol.CallOffer
import devchats.protocol.CallReject
import devchats.protocol.ChannelInfo
import devchats.protocol.ChannelListItems
import devchats.protocol.ChannelListRequest
import devchats.protocol.DmSend
import devchats.protocol.FILE_CHUNK_SIZE
import devchats.protocol.FileAbort
import devchats.protocol.FileAccept
import devchats.protocol.FileChunk
import devchats.protocol.FileDone
import devchats.protocol.FileOffer
import devchats.protocol.Hello
import devchats.protocol.MailboxSync
import devchats.protocol.Message
import devchats.protocol.MessageInfo
import devchats.protocol.MsgHistoryItems
import devchats.protocol.MsgHistoryRequest
import devchats.protocol.MsgSend
import devchats.protocol.VideoFrame
import devchats.protocol.VideoStart
import devchats.protocol.VideoStop
import devchats.server.DevChatsServer
import devchats.server.FileDirection
import devchats.server.FileRecord
import devchats.server.FileStatus
import devchats.server.NodeConfig
import devchats.server.NodeEvents
import devchats.server.NodeStore
import devchats.server.Peer
import devchats.server.PeerStatus
import devchats.server.EmojiInfo
import devchats.server.RoleInfo
import devchats.server.ServerInfo
import devchats.server.ServerStatus
import devchats.server.SoundInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Статус узла в списке сайдбара. */
enum class NodeUiStatus { Local, Connecting, Online, Waiting, Offline, Error }

/** Запись в списке узлов (наблюдаемые поля — для Compose). */
class KnownNodeUi(
    val key: String,
    name: String,
    address: String,
    status: NodeUiStatus,
    val isLocal: Boolean = false,
) {
    var name by mutableStateOf(name)
    var address by mutableStateOf(address)
    var status by mutableStateOf(status)
}

/** Элемент беседы: текстовое сообщение или файл. */
sealed interface ConvItem {
    data class Text(val info: MessageInfo) : ConvItem
    data class File(val record: FileRecord) : ConvItem
}

/**
 * Состояние приложения: аккаунт, локальный узел (встроенный сервер),
 * сервера и каналы, подключения, беседы (DM и каналы), файлы и звонки.
 *
 * Ключи бесед: `dm:<nodeId>` (личная), `ch:<channelId>` (мой канал),
 * `ch:<channelId>@<nodeKey>` (канал удалённого узла).
 */
class AppState(private val scope: CoroutineScope) : NodeEvents {

    // --- аккаунт ---

    var loggedIn by mutableStateOf(false)
        private set
    var authBusy by mutableStateOf(false)
        private set
    var authError by mutableStateOf<String?>(null)
        private set
    var nodeAvatarPath by mutableStateOf<String?>(null)
        private set

    // --- локальный узел ---

    var serverStatus by mutableStateOf<ServerStatus>(ServerStatus.Starting)
        private set
    var nodeId by mutableStateOf("")
        private set
    var nodeName by mutableStateOf("")
        private set
    var boundPort by mutableStateOf(0)
        private set

    // --- узлы и подключения ---

    /** Удалённые узлы (исходящие подключения и подключённые к нам). */
    val nodes = mutableStateListOf<KnownNodeUi>()
    val pendingRequests = mutableStateListOf<Peer>()

    /** Ключ узла -> nodeId удалённого узла (для DM-бесед). */
    val nodeIdByKey = mutableMapOf<String, String>()

    // --- сервера и каналы ---

    /** Мои сервера (гильдии). */
    val servers = mutableStateListOf<ServerInfo>()
    /** Каналы по серверам: serverId -> каналы. */
    val channelsByServer = mutableStateMapOf<String, SnapshotStateList<ChannelInfo>>()
    /** Эмодзи по серверам: serverId -> эмодзи. */
    val emojisByServer = mutableStateMapOf<String, SnapshotStateList<EmojiInfo>>()
    /** Звуки звуковой панели: serverId -> звуки. */
    val soundsByServer = mutableStateMapOf<String, SnapshotStateList<SoundInfo>>()
    /** Роли по серверам: serverId -> роли. */
    val rolesByServer = mutableStateMapOf<String, SnapshotStateList<RoleInfo>>()
    /** Каналы удалённых узлов: nodeKey -> каналы. */
    val remoteChannels = mutableStateMapOf<String, SnapshotStateList<ChannelInfo>>()

    // --- беседы ---

    /** Принятые пиры — цели для личных сообщений. */
    val dmPeers = mutableStateListOf<Peer>()
    /** Сообщения и файлы по беседам: convKey -> список. */
    val messages = mutableStateMapOf<String, SnapshotStateList<ConvItem>>()
    /** Непрочитанные: convKey -> счётчик. */
    val unread = mutableStateMapOf<String, Int>()

    /** Беседа, открытая сейчас (для сброса непрочитанных). */
    var openConvKey by mutableStateOf<String?>(null)

    private val remoteByKey = mutableMapOf<String, RemoteNode>()
    private val peerNodeIdToRemote = mutableMapOf<String, RemoteNode>()

    private var server: DevChatsServer? = null
    private val store: NodeStore by lazy { NodeStore.openDefault() }
    private val nodeClient = NodeClient(
        client = HttpClient(CIO) { install(WebSockets) },
        scope = scope,
    )

    // --- звонки ---

    /** Менеджер голосовых звонков (Opus поверх того же маршрута, что и DM). */
    val callManager = CallManager(
        scope = scope,
        nodeIdProvider = { nodeId },
        displayNameProvider = { nodeName },
        route = ::routeCall,
    )

    // --- жизненный цикл ---

    /** Вызывается при старте приложения — до входа сервер не поднимается. */
    fun init() {
        // Открываем БД заранее, чтобы экран входа не ждал первого запроса.
        scope.launch { runCatching { store.hasAccount() } }
    }

    /** Создать аккаунт и войти. */
    fun register(login: String, password: String, displayName: String, avatarPath: String?) {
        scope.launch {
            authBusy = true
            authError = null
            try {
                val storedAvatar = avatarPath?.let { runCatching { store.saveAvatar(it) }.getOrNull() }
                val cfg = store.register(login, password, displayName, storedAvatar)
                onAuthenticated(cfg, storedAvatar)
            } catch (e: Exception) {
                authError = e.message ?: "Не удалось создать аккаунт"
            } finally {
                authBusy = false
            }
        }
    }

    /** Войти в существующий аккаунт. */
    fun login(login: String, password: String) {
        scope.launch {
            authBusy = true
            authError = null
            try {
                val cfg = store.login(login, password)
                if (cfg == null) {
                    authError = "Неверный логин или пароль"
                } else {
                    onAuthenticated(cfg, store.avatarPath())
                }
            } finally {
                authBusy = false
            }
        }
    }

    /** Выйти: остановить узел и вернуться на экран входа. */
    fun logout() {
        scope.launch {
            stop()
            loggedIn = false
        }
    }

    /** Сменить аватар своего аккаунта. Возвращает сохранённый путь. */
    fun setOwnAvatar(sourcePath: String): String? {
        val saved = runCatching { store.saveAvatar(sourcePath) }.getOrNull() ?: return null
        store.setAvatar(saved)
        nodeAvatarPath = saved
        return saved
    }

    private fun onAuthenticated(cfg: NodeConfig, avatar: String?) {
        scope.launch {
            try {
                nodeId = cfg.nodeId
                nodeName = cfg.displayName
                nodeAvatarPath = avatar
                loggedIn = true

                servers.addAll(store.servers())
                for (srv in servers.toList()) {
                    channelsByServer[srv.id] = store.channels(srv.id).toMutableStateList()
                    emojisByServer[srv.id] = store.serverEmojis(srv.id).toMutableStateList()
                    soundsByServer[srv.id] = store.serverSounds(srv.id).toMutableStateList()
                    rolesByServer[srv.id] = store.serverRoles(srv.id).toMutableStateList()
                }
                dmPeers.addAll(store.peersByStatus(PeerStatus.Accepted))

                val srv = DevChatsServer(cfg, store, this@AppState)
                server = srv
                srv.start()
                boundPort = srv.boundPort()
                serverStatus = ServerStatus.Running
            } catch (e: Exception) {
                serverStatus = ServerStatus.Failed(e.message ?: e.toString())
            }
        }
    }

    // --- сервера ---

    /** Создать сервер с именем, описанием и аватаром (аватар копируется в данные узла). */
    fun createServer(name: String, description: String, avatarPath: String?) {
        val storedAvatar = avatarPath?.let { runCatching { store.saveAvatar(it) }.getOrNull() }
        val srv = store.addServer(name, description, storedAvatar)
        servers.add(srv)
        channelsByServer[srv.id] = mutableStateListOf()
    }

    /** Изменить имя и описание сервера. */
    fun updateServer(id: String, name: String, description: String) {
        store.updateServer(id, name, description)
        val idx = servers.indexOfFirst { it.id == id }
        if (idx >= 0) {
            servers[idx] = servers[idx].copy(
                name = name.trim().ifEmpty { "Сервер" },
                description = description.trim(),
            )
        }
    }

    /** Сменить аватар сервера. Возвращает сохранённый путь. */
    fun setServerAvatar(id: String, sourcePath: String): String? {
        val saved = runCatching { store.saveAvatar(sourcePath) }.getOrNull() ?: return null
        store.setServerAvatar(id, saved)
        val idx = servers.indexOfFirst { it.id == id }
        if (idx >= 0) servers[idx] = servers[idx].copy(avatarPath = saved)
        return saved
    }

    /** Удалить сервер вместе с каналами и сообщениями. */
    fun deleteServer(id: String) {
        store.deleteServer(id)
        channelsByServer.remove(id)?.forEach { channel ->
            messages.remove("ch:${channel.id}")
            unread.remove("ch:${channel.id}")
        }
        emojisByServer.remove(id)
        soundsByServer.remove(id)
        rolesByServer.remove(id)
        servers.removeAll { it.id == id }
    }

    // --- настройки сервера ---

    /** Обновить настройки сервера (баннер, вход/выход, бездействие, эмодзи). */
    fun updateServerConfig(
        id: String,
        bannerColor: String,
        joinLeaveEnabled: Boolean,
        joinLeaveChannelIds: List<String>,
        afkEnabled: Boolean,
        afkChannelId: String?,
        emojiAutocomplete: Boolean,
        emojiConvert: Boolean,
    ) {
        store.updateServerConfig(id, bannerColor, joinLeaveEnabled, joinLeaveChannelIds, afkEnabled, afkChannelId, emojiAutocomplete, emojiConvert)
        val idx = servers.indexOfFirst { it.id == id }
        if (idx >= 0) {
            servers[idx] = servers[idx].copy(
                bannerColor = bannerColor,
                joinLeaveEnabled = joinLeaveEnabled,
                joinLeaveChannelIds = joinLeaveChannelIds,
                afkEnabled = afkEnabled,
                afkChannelId = afkChannelId,
                emojiAutocomplete = emojiAutocomplete,
                emojiConvert = emojiConvert,
            )
        }
    }

    /** Добавить эмодзи (файл копируется в данные узла). null — не удалось сохранить файл. */
    fun addEmoji(serverId: String, name: String, sourcePath: String): EmojiInfo? {
        val saved = runCatching { store.saveServerFile(serverId, "emojis", sourcePath) }.getOrNull() ?: return null
        val emoji = store.addEmoji(serverId, name, saved)
        emojisByServer.getOrPut(serverId) { mutableStateListOf() }.add(emoji)
        return emoji
    }

    fun deleteEmoji(serverId: String, id: String) {
        store.deleteEmoji(id)
        emojisByServer[serverId]?.removeAll { it.id == id }
    }

    /** Добавить звук в звуковую панель. null — не удалось сохранить файл. */
    fun addSound(serverId: String, name: String, sourcePath: String, keyBind: String): SoundInfo? {
        val saved = runCatching { store.saveServerFile(serverId, "sounds", sourcePath) }.getOrNull() ?: return null
        val sound = store.addSound(serverId, name, saved, keyBind)
        soundsByServer.getOrPut(serverId) { mutableStateListOf() }.add(sound)
        return sound
    }

    fun updateSound(serverId: String, id: String, keyBind: String, trimStartMs: Long, trimEndMs: Long) {
        store.updateSound(id, keyBind, trimStartMs, trimEndMs)
        val list = soundsByServer[serverId] ?: return
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = list[idx].copy(keyBind = keyBind.trim(), trimStartMs = trimStartMs, trimEndMs = trimEndMs)
    }

    fun deleteSound(serverId: String, id: String) {
        store.deleteSound(id)
        soundsByServer[serverId]?.removeAll { it.id == id }
    }

    // --- роли ---

    fun addRole(serverId: String, name: String): RoleInfo {
        val role = store.addRole(serverId, name)
        rolesByServer.getOrPut(serverId) { mutableStateListOf() }.add(role)
        return role
    }

    fun updateRole(serverId: String, id: String, name: String, color: String, showSeparately: Boolean, mentionable: Boolean) {
        store.updateRole(id, name, color, showSeparately, mentionable)
        val list = rolesByServer[serverId] ?: return
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(name = name.trim(), color = color, showSeparately = showSeparately, mentionable = mentionable)
        }
    }

    fun deleteRole(serverId: String, id: String) {
        store.deleteRole(id)
        rolesByServer[serverId]?.removeAll { it.id == id }
    }

    /** Переупорядочить роли (первый элемент — самая верхняя). */
    fun reorderRoles(serverId: String, orderedIds: List<String>) {
        store.reorderRoles(serverId, orderedIds)
        val list = rolesByServer[serverId] ?: return
        val reordered = orderedIds.mapNotNull { id -> list.firstOrNull { it.id == id } }
        list.clear()
        list.addAll(reordered)
    }

    // --- каналы ---

    /** Создать канал в сервере [serverId]. */
    fun createChannel(serverId: String, name: String, kind: String, description: String) {
        val channel = store.addChannel(serverId, name, kind, description)
        channelsByServer.getOrPut(serverId) { mutableStateListOf() }.add(channel)
    }

    /** Переименовать / сменить тип / настроить канал. */
    fun updateChannel(channelId: String, name: String, kind: String, description: String) {
        store.updateChannel(channelId, name, kind, description)
        val updated = store.channel(channelId) ?: return
        for (list in channelsByServer.values) {
            val idx = list.indexOfFirst { it.id == channelId }
            if (idx >= 0) {
                list[idx] = updated
                return
            }
        }
    }

    /** Удалить канал вместе с сообщениями. */
    fun deleteChannel(channelId: String) {
        store.deleteChannel(channelId)
        for (list in channelsByServer.values) {
            list.removeAll { it.id == channelId }
        }
        messages.remove("ch:$channelId")
        unread.remove("ch:$channelId")
    }

    /** Канал по id (для диалогов настройки). */
    fun channelById(channelId: String): ChannelInfo? =
        channelsByServer.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == channelId } }

    // --- подключения ---

    /** Подключиться к удалённому узлу по [host]:[port]. */
    fun addServer(host: String, port: Int) {
        if (nodeId.isEmpty()) return
        val remote = nodeClient.connect(host, port, Hello(nodeId = nodeId, displayName = nodeName))
        val key = "out-${System.identityHashCode(remote)}"
        remoteByKey[key] = remote
        nodes.add(KnownNodeUi(key, "$host:$port", "$host:$port", NodeUiStatus.Connecting))

        scope.launch {
            remote.state.collect { state ->
                val entry = nodes.firstOrNull { it.key == key } ?: return@collect
                when (state) {
                    is NodeConnectionState.Connected -> {
                        entry.name = state.displayName
                        entry.status = NodeUiStatus.Online
                        nodeIdByKey[key] = state.nodeId
                        peerNodeIdToRemote[state.nodeId] = remote
                        // доставляем свой outbox, тянем чужой, запрашиваем каналы
                        syncOutbox(remote, store, state.nodeId)
                    }
                    NodeConnectionState.WaitingForApproval -> entry.status = NodeUiStatus.Waiting
                    is NodeConnectionState.Rejected -> entry.status = NodeUiStatus.Error
                    NodeConnectionState.Connecting -> entry.status = NodeUiStatus.Connecting
                    is NodeConnectionState.Disconnected -> {
                        if (entry.status != NodeUiStatus.Error) entry.status = NodeUiStatus.Offline
                        callManager.connectionLost()
                    }
                    is NodeConnectionState.Failed -> {
                        entry.status = NodeUiStatus.Error
                        callManager.connectionLost()
                    }
                }
            }
        }
        scope.launch {
            remote.messages.collect { envelope ->
                handleIncomingProtocolMessage(key, envelope.payload)
            }
        }
    }

    // --- звонки: действия ---

    /** Можно ли позвонить пиру: есть исходящее соединение или он подключён к нам. */
    fun canCall(peerNodeId: String): Boolean =
        peerNodeIdToRemote.containsKey(peerNodeId) || server?.isConnected(peerNodeId) == true

    /** Позвонить собеседнику из DM-беседы [convKey]. */
    fun startCall(convKey: String) {
        if (!convKey.startsWith("dm:")) return
        val peer = convKey.removePrefix("dm:")
        if (!canCall(peer)) return
        val name = dmPeers.firstOrNull { it.nodeId == peer }?.displayName ?: peer
        callManager.startCall(peer, name)
    }

    /** Подходит ли беседа для звонка (DM с доступным собеседником). */
    fun isCallableConversation(convKey: String): Boolean {
        if (!convKey.startsWith("dm:")) return false
        return canCall(convKey.removePrefix("dm:"))
    }

    // --- беседы ---

    /** Открыть беседу: сбросить непрочитанные, загрузить историю. */
    fun selectConversation(key: String) {
        openConvKey = key
        unread.remove(key)
        ensureConversation(key)
        if (key.startsWith("ch:") && key.contains('@')) {
            val channelId = key.removePrefix("ch:").substringBefore('@')
            val nodeKey = key.substringAfter('@')
            remoteByKey[nodeKey]?.let { remote ->
                scope.launch { runCatching { remote.send(MsgHistoryRequest(channelId, limit = 200)) } }
            }
        }
    }

    /** Отправить сообщение в беседу [convKey]. */
    fun sendMessage(convKey: String, text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        when {
            convKey.startsWith("dm:") -> sendDm(convKey.removePrefix("dm:"), text, now)
            convKey.startsWith("ch:") -> {
                val rest = convKey.removePrefix("ch:")
                val at = rest.indexOf('@')
                if (at >= 0) {
                    sendRemoteChannelMessage(rest.substring(at + 1), rest.substring(0, at), text, now)
                } else {
                    sendLocalChannelMessage(rest, text, now)
                }
            }
        }
    }

    /** Отправить файл в беседу [convKey] (чанками, с проверкой SHA-256). */
    fun sendFile(convKey: String, path: String) {
        val file = File(path)
        if (!file.isFile || !file.canRead()) return
        scope.launch(Dispatchers.IO) {
            try {
                val fileId = UUID.randomUUID().toString()
                val size = file.length()
                val sha = sha256File(file)
                val (channelId, peerNodeId) = convTarget(convKey)
                val record = store.startOutgoingFile(fileId, file.name, size, sha, channelId, peerNodeId, file.absolutePath)
                appendMessage(convKey, ConvItem.File(record))

                if (!routeSend(convKey, FileOffer(fileId, file.name, size, sha, channelId, nodeId))) {
                    store.abortFile(fileId, "Собеседник офлайн")
                    refreshFileItem(fileId)
                    return@launch
                }

                var offset = 0L
                var index = 0
                Files.newInputStream(file.toPath()).use { input ->
                    while (offset < size) {
                        val remaining = size - offset
                        val toRead = minOf(FILE_CHUNK_SIZE.toLong(), remaining).toInt()
                        val buffer = ByteArray(toRead)
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val data = if (read == buffer.size) buffer else buffer.copyOf(read)
                        if (!routeSend(convKey, FileChunk(fileId, index, data))) {
                            store.abortFile(fileId, "Соединение прервано")
                            refreshFileItem(fileId)
                            return@launch
                        }
                        offset += read
                        index++
                        store.updateFileProgress(fileId, offset)
                        refreshFileItem(fileId)
                    }
                }
                routeSend(convKey, FileDone(fileId))
                refreshFileItem(fileId)
            } catch (_: Exception) {
                // передача прервана — запись останется в статусе Transferring
            }
        }
    }

    fun accept(nodeId: String) {
        pendingRequests.removeAll { it.nodeId == nodeId }
        scope.launch { server?.accept(nodeId) }
    }

    fun deny(nodeId: String) {
        pendingRequests.removeAll { it.nodeId == nodeId }
        scope.launch { server?.deny(nodeId) }
    }

    fun stop() {
        callManager.hangup()
        server?.stop()
        server = null
        serverStatus = ServerStatus.Stopped
    }

    // --- отправка ---

    private fun sendDm(peerNodeId: String, text: String, now: Long) {
        val msg = DmSend(messageId = newUuid(), to = peerNodeId, text = text, timestamp = now)
        val info = store.insertMessage(msg.messageId, null, nodeId, peerNodeId, text, now, delivered = false)
        appendMessage("dm:$peerNodeId", ConvItem.Text(info))
        scope.launch {
            if (routeSend("dm:$peerNodeId", msg)) {
                store.markDelivered(msg.messageId)
            }
        }
    }

    private fun sendLocalChannelMessage(channelId: String, text: String, now: Long) {
        val msg = MsgSend(newUuid(), channelId, nodeId, text, now)
        val info = store.insertMessage(msg.messageId, channelId, nodeId, null, text, now)
        appendMessage("ch:$channelId", ConvItem.Text(info))
        scope.launch { server?.broadcastToConnected(msg) }
    }

    private fun sendRemoteChannelMessage(nodeKey: String, channelId: String, text: String, now: Long) {
        val remote = remoteByKey[nodeKey] ?: return
        val msg = MsgSend(newUuid(), channelId, nodeId, text, now)
        val info = MessageInfo(msg.messageId, channelId, nodeId, text, now)
        appendMessage("ch:$channelId@$nodeKey", ConvItem.Text(info))
        scope.launch { runCatching { remote.send(msg) } }
    }

    /**
     * Доставляет сообщение в беседу: DM — по исходящему соединению или
     * входящей сессии; канал — на хост или рассылкой по своим сессиям.
     */
    private suspend fun routeSend(convKey: String, message: Message): Boolean = when {
        convKey.startsWith("dm:") -> {
            val peer = convKey.removePrefix("dm:")
            val remote = peerNodeIdToRemote[peer]
            if (remote != null) {
                runCatching { remote.send(message); true }.getOrDefault(false)
            } else {
                server?.sendTo(peer, message) == true
            }
        }
        convKey.startsWith("ch:") -> {
            val rest = convKey.removePrefix("ch:")
            val at = rest.indexOf('@')
            if (at >= 0) {
                val remote = remoteByKey[rest.substring(at + 1)] ?: return false
                runCatching { remote.send(message); true }.getOrDefault(false)
            } else {
                server?.broadcastToConnected(message)
                true
            }
        }
        else -> false
    }

    /** Маршрут сообщений звонка — тот же, что у DM (исходящее соединение или входящая сессия). */
    private suspend fun routeCall(peerNodeId: String, message: Message): Boolean =
        routeSend("dm:$peerNodeId", message)

    /** Для беседы возвращает (channelId, peerNodeId) файла. */
    private fun convTarget(convKey: String): Pair<String?, String> = when {
        convKey.startsWith("dm:") -> null to convKey.removePrefix("dm:")
        convKey.startsWith("ch:") -> {
            val rest = convKey.removePrefix("ch:")
            val at = rest.indexOf('@')
            if (at >= 0) {
                val nodeKey = rest.substring(at + 1)
                rest.substring(0, at) to (nodeIdByKey[nodeKey] ?: nodeId)
            } else {
                rest to nodeId
            }
        }
        else -> null to nodeId
    }

    // --- входящие ---

    private suspend fun handleIncomingProtocolMessage(nodeKey: String, payload: Any) {
        when (payload) {
            is DmSend -> {
                val peer = nodeIdByKey[nodeKey] ?: return
                val info = store.insertMessage(payload.messageId, null, peer, nodeId, payload.text, payload.timestamp)
                appendIncoming("dm:$peer", ConvItem.Text(info))
            }
            is MsgSend -> {
                val info = store.insertMessage(payload.messageId, payload.channelId, payload.authorNodeId, null, payload.text, payload.timestamp)
                appendIncoming("ch:${payload.channelId}@$nodeKey", ConvItem.Text(info))
            }
            is ChannelListItems -> {
                remoteChannels[nodeKey] = payload.channels.toMutableStateList()
            }
            is MsgHistoryItems -> {
                val key = "ch:${payload.channelId}@$nodeKey"
                val list = messages.getOrPut(key) { mutableStateListOf() }
                list.clear()
                list.addAll(payload.messages.map { ConvItem.Text(it) })
            }
            is FileOffer -> {
                val record = withContext(Dispatchers.IO) {
                    store.beginIncomingFile(payload.fileId, payload.fileName, payload.size, payload.sha256, payload.channelId, payload.authorNodeId)
                }
                val convKey = if (record.channelId != null) "ch:${record.channelId}@$nodeKey" else "dm:${record.peerNodeId}"
                appendIncoming(convKey, ConvItem.File(record))
                // авто-приём с учётом уже принятых байт (докачка)
                val remote = remoteByKey[nodeKey] ?: return
                runCatching { remote.send(FileAccept(payload.fileId, record.receivedBytes)) }
            }
            is FileChunk -> {
                withContext(Dispatchers.IO) { store.appendChunk(payload.fileId, payload.data) }
                refreshFileItem(payload.fileId)
            }
            is FileDone -> {
                withContext(Dispatchers.IO) { store.completeFile(payload.fileId) }
                refreshFileItem(payload.fileId)
            }
            is FileAbort -> {
                withContext(Dispatchers.IO) { store.abortFile(payload.fileId, payload.reason) }
                refreshFileItem(payload.fileId)
            }
            is CallOffer -> callManager.onOffer(payload.callId, payload.fromNodeId, payload.fromName)
            is CallAccept -> callManager.onAccept(payload.callId, payload.fromNodeId)
            is CallReject -> callManager.onReject(payload.callId, payload.fromNodeId, payload.reason)
            is CallHangup -> callManager.onHangup(payload.callId, payload.fromNodeId)
            is CallAudio -> callManager.onAudio(payload.callId, payload.data)
            is VideoStart -> callManager.onVideoStart(payload.callId, payload.fromNodeId, payload.kind, payload.width, payload.height)
            is VideoFrame -> callManager.onVideoFrame(payload.callId, payload.kind, payload.data)
            is VideoStop -> callManager.onVideoStop(payload.callId, payload.kind)
            else -> Unit
        }
    }

    // --- события сервера (NodeEvents) ---

    override fun onMessageReceived(message: MessageInfo) {
        val key = if (message.channelId != null) "ch:${message.channelId}" else "dm:${message.authorNodeId}"
        appendIncoming(key, ConvItem.Text(message))
    }

    override fun onFileTransfer(record: FileRecord) {
        val key = if (record.channelId != null) "ch:${record.channelId}" else "dm:${record.peerNodeId}"
        appendIncoming(key, ConvItem.File(record))
    }

    override fun onFileTransferProgress(fileId: String, receivedBytes: Long) {
        refreshFileItem(fileId)
    }

    override fun onFileTransferFinished(fileId: String) {
        refreshFileItem(fileId)
    }

    override fun onConnectionRequest(peer: Peer) {
        pendingRequests.add(peer)
    }

    override fun onPeerConnected(peer: Peer) {
        nodeIdByKey[peer.nodeId] = peer.nodeId
        if (dmPeers.none { it.nodeId == peer.nodeId }) {
            dmPeers.add(peer)
        }
        val existing = nodes.firstOrNull { it.key == peer.nodeId }
        if (existing == null) {
            nodes.add(
                KnownNodeUi(
                    key = peer.nodeId,
                    name = peer.displayName,
                    address = peer.address ?: "?",
                    status = NodeUiStatus.Online,
                )
            )
        } else {
            existing.name = peer.displayName
            existing.status = NodeUiStatus.Online
        }
    }

    override fun onPeerDisconnected(nodeId: String) {
        nodes.firstOrNull { it.key == nodeId }?.status = NodeUiStatus.Offline
        callManager.connectionLost()
    }

    override fun onIncomingCall(call: CallOffer) = callManager.onOffer(call.callId, call.fromNodeId, call.fromName)

    override fun onCallAccepted(call: CallAccept) = callManager.onAccept(call.callId, call.fromNodeId)

    override fun onCallRejected(call: CallReject) = callManager.onReject(call.callId, call.fromNodeId, call.reason)

    override fun onCallHangup(call: CallHangup) = callManager.onHangup(call.callId, call.fromNodeId)

    override fun onCallAudio(call: CallAudio) = callManager.onAudio(call.callId, call.data)

    override fun onVideoStart(video: VideoStart) =
        callManager.onVideoStart(video.callId, video.fromNodeId, video.kind, video.width, video.height)

    override fun onVideoFrame(video: VideoFrame) = callManager.onVideoFrame(video.callId, video.kind, video.data)

    override fun onVideoStop(video: VideoStop) = callManager.onVideoStop(video.callId, video.kind)

    // --- вспомогательное ---

    private fun appendIncoming(convKey: String, item: ConvItem) {
        appendMessage(convKey, item)
        if (convKey != openConvKey) {
            unread[convKey] = (unread[convKey] ?: 0) + 1
        }
    }

    private fun appendMessage(convKey: String, item: ConvItem) {
        val list = messages.getOrPut(convKey) { mutableStateListOf() }
        list.add(item)
    }

    /** Обновляет карточку файла во всех открытых списках. */
    private fun refreshFileItem(fileId: String) {
        val record = store.fileRecord(fileId) ?: return
        val updated = ConvItem.File(record)
        for (list in messages.values) {
            val index = list.indexOfFirst { (it as? ConvItem.File)?.record?.fileId == fileId }
            if (index >= 0) {
                list[index] = updated
                return
            }
        }
    }

    private fun ensureConversation(key: String) {
        if (messages.containsKey(key)) return
        val list = mutableStateListOf<ConvItem>()
        when {
            key.startsWith("dm:") -> {
                val peer = key.removePrefix("dm:")
                list.addAll(store.dmConversation(peer).map { ConvItem.Text(it) })
                list.addAll(store.filesForDm(peer).map { ConvItem.File(it) })
            }
            key.startsWith("ch:") && !key.contains('@') -> {
                val channelId = key.removePrefix("ch:")
                list.addAll(store.channelMessages(channelId, 200).map { ConvItem.Text(it) })
                list.addAll(store.filesForChannel(channelId).map { ConvItem.File(it) })
            }
        }
        list.sortBy { item ->
            when (item) {
                is ConvItem.Text -> item.info.timestamp
                is ConvItem.File -> item.record.timestamp
            }
        }
        messages[key] = list
    }

    private fun newUuid(): String = UUID.randomUUID().toString()
}

/** SHA-256 файла (hex). */
private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file.toPath()).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Синхронизация почтового ящика при установке соединения:
 * доставляем свой outbox для [peerNodeId], затем просим узел отдать его
 * outbox и запрашиваем каналы.
 */
suspend fun syncOutbox(remote: RemoteNode, store: NodeStore, peerNodeId: String) {
    for (msg in store.pendingOutboxFor(peerNodeId)) {
        try {
            remote.send(DmSend(msg.messageId, peerNodeId, msg.text, msg.timestamp))
        } catch (_: Exception) {
            // соединение могло оборваться — вернётся при следующей синхронизации
        }
    }
    store.markAllDelivered(peerNodeId)
    remote.send(MailboxSync)
    remote.send(ChannelListRequest)
}
