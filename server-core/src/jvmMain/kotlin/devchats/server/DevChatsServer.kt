package devchats.server

import devchats.protocol.CallAccept
import devchats.protocol.CallAudio
import devchats.protocol.CallHangup
import devchats.protocol.CallOffer
import devchats.protocol.CallReject
import devchats.protocol.ChannelListItems
import devchats.protocol.ChannelListRequest
import devchats.protocol.DevChatsJson
import devchats.protocol.DmSend
import devchats.protocol.Envelope
import devchats.protocol.FileAbort
import devchats.protocol.FileAccept
import devchats.protocol.FileChunk
import devchats.protocol.FileDone
import devchats.protocol.FileOffer
import devchats.protocol.Hello
import devchats.protocol.HelloAck
import devchats.protocol.MailboxSync
import devchats.protocol.Message
import devchats.protocol.MessageInfo
import devchats.protocol.MsgHistoryItems
import devchats.protocol.MsgHistoryRequest
import devchats.protocol.MsgSend
import devchats.protocol.PROTOCOL_VERSION
import devchats.protocol.Presence
import devchats.protocol.VideoFrame
import devchats.protocol.VideoStart
import devchats.protocol.VideoStop
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Ответ `GET /health` — проверка, что узел жив. */
@Serializable
data class HealthResponse(
    val status: String,
    val nodeId: String,
    val displayName: String,
    val protocolVersion: Int,
    val appVersion: String,
)

private object NodeEventsDefault : NodeEvents

/**
 * Встроенный сервер узла DevChats.
 *
 * Один и тот же класс используется десктоп-приложением (встраивается при
 * запуске) и будущим постоянным сервером на Linux (server-standalone).
 *
 * - `GET /health` — статус узла;
 * - `/ws` — WebSocket-канал протокола. Первым сообщением клиент шлёт [Hello],
 *   сервер проверяет версию протокола и статус пира (accept/deny/block)
 *   и отвечает [HelloAck].
 */
class DevChatsServer(
    private val config: NodeConfig,
    private val store: NodeStore,
    private val events: NodeEvents = NodeEventsDefault,
) {

    private var server: EmbeddedServer<*, *>? = null

    /** Активные WebSocket-сессии подключённых узлов (pending + accepted). */
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    private val displayNames = ConcurrentHashMap<String, String>()
    private val peerAddresses = ConcurrentHashMap<String, String>()

    /**
     * Запускает сервер (слушает все интерфейсы). Если [NodeConfig.port] занят
     * (например, второй экземпляр на той же машине) — берёт свободный порт.
     */
    suspend fun start() {
        server = try {
            embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
                module(this)
            }.start(wait = false)
        } catch (e: java.net.BindException) {
            embeddedServer(Netty, port = 0, host = "0.0.0.0") {
                module(this)
            }.start(wait = false)
        }
    }

    /** Реальный порт, на котором слушает сервер (после [start]). */
    suspend fun boundPort(): Int =
        server?.engine?.resolvedConnectors()?.firstOrNull()?.port ?: config.port

    fun stop() {
        server?.stop()
        server = null
        sessions.clear()
        displayNames.clear()
        peerAddresses.clear()
    }

    /** Маршруты узла. Вынесено отдельно, чтобы тестировать без реального движка. */
    fun module(application: Application) {
        with(application) {
            install(ContentNegotiation) {
                json(DevChatsJson)
            }
            install(WebSockets)
            routing {
                get("/health") {
                    call.respond(
                        HealthResponse(
                            status = "ok",
                            nodeId = config.nodeId,
                            displayName = config.displayName,
                            protocolVersion = PROTOCOL_VERSION,
                            appVersion = APP_VERSION,
                        )
                    )
                }
                webSocket("/ws") {
                    handleConnection()
                }
            }
        }
    }

    // --- управление пирами (вызывается из UI) ---

    /** Подтверждает запрос на подключение пира. */
    suspend fun accept(nodeId: String) {
        store.setPeerStatus(nodeId, PeerStatus.Accepted)
        val session = sessions[nodeId] ?: return
        val name = displayNames[nodeId] ?: nodeId
        session.send(ackEnvelope(accepted = true))
        events.onPeerConnected(Peer(nodeId, name, peerAddresses[nodeId], PeerStatus.Accepted))
    }

    /** Отклоняет запрос и удаляет пира (повторное подключение снова спросит). */
    suspend fun deny(nodeId: String) {
        store.deletePeer(nodeId)
        val session = sessions[nodeId] ?: return
        session.send(ackEnvelope(accepted = false, reason = "Отклонено"))
        session.close(CloseReason(CloseReason.Codes.NORMAL, "Отклонено"))
    }

    /** Блокирует пира навсегда. */
    suspend fun block(nodeId: String) {
        store.setPeerStatus(nodeId, PeerStatus.Blocked)
        val session = sessions[nodeId] ?: return
        session.send(ackEnvelope(accepted = false, reason = "Заблокировано"))
        session.close(CloseReason(CloseReason.Codes.NORMAL, "Заблокировано"))
    }

    // --- внутренности ---

    private sealed interface Decision {
        data object Accepted : Decision
        data object Pending : Decision
        data class Denied(val reason: String) : Decision
    }

    private suspend fun DefaultWebSocketServerSession.handleConnection() {
        val hello = receiveHello() ?: run {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Ожидалось hello"))
            return
        }
        val address = call.request.origin.remoteHost

        when (val decision = decide(hello)) {
            is Decision.Denied -> {
                send(ackEnvelope(accepted = false, reason = decision.reason))
                close(CloseReason(CloseReason.Codes.NORMAL, "Отклонено"))
                return
            }
            Decision.Pending -> {
                displayNames[hello.nodeId] = hello.displayName
                peerAddresses[hello.nodeId] = address
                sessions[hello.nodeId] = this
                store.upsertPeer(Peer(hello.nodeId, hello.displayName, address, PeerStatus.Pending))
                send(ackEnvelope(accepted = false, reason = "pending"))
                events.onConnectionRequest(Peer(hello.nodeId, hello.displayName, address, PeerStatus.Pending))
            }
            Decision.Accepted -> {
                displayNames[hello.nodeId] = hello.displayName
                peerAddresses[hello.nodeId] = address
                sessions[hello.nodeId]?.let { old -> try { old.close() } catch (_: Exception) {} }
                sessions[hello.nodeId] = this
                store.upsertPeer(Peer(hello.nodeId, hello.displayName, address, PeerStatus.Accepted))
                store.markSeen(hello.nodeId)
                send(ackEnvelope(accepted = true))
                broadcastPresence(hello.nodeId, online = true)
                events.onPeerConnected(Peer(hello.nodeId, hello.displayName, address, PeerStatus.Accepted))
            }
        }

        try {
            for (frame in incoming) {
                if (frame is Frame.Close) break
                if (frame !is Frame.Text) continue
                val envelope = runCatching {
                    DevChatsJson.decodeFromString(Envelope.serializer(), frame.readText())
                }.getOrNull() ?: continue
                handleMessage(hello.nodeId, envelope)
            }
        } finally {
            sessions.remove(hello.nodeId, this)
            broadcastPresence(hello.nodeId, online = false)
            events.onPeerDisconnected(hello.nodeId)
        }
    }

    /**
     * Обрабатывает сообщения протокола от подключённого узла [fromNodeId]:
     * DM, сообщения каналов, синхронизацию почтового ящика, запросы каналов
     * и истории.
     */
    private suspend fun DefaultWebSocketServerSession.handleMessage(fromNodeId: String, envelope: Envelope) {
        when (val m = envelope.payload) {
            is DmSend -> {
                store.insertMessage(m.messageId, null, fromNodeId, config.nodeId, m.text, m.timestamp)
                events.onMessageReceived(MessageInfo(m.messageId, null, fromNodeId, m.text, m.timestamp))
            }
            is MsgSend -> {
                store.insertMessage(m.messageId, m.channelId, m.authorNodeId, null, m.text, m.timestamp)
                events.onMessageReceived(MessageInfo(m.messageId, m.channelId, m.authorNodeId, m.text, m.timestamp))
                // ретранслируем другим подключённым узлам
                broadcastToConnected(m, exceptNodeId = fromNodeId)
            }
            is MailboxSync -> {
                val outbox = store.pendingOutboxFor(fromNodeId)
                for (msg in outbox) {
                    send(envelope(DmSend(msg.messageId, fromNodeId, msg.text, msg.timestamp)))
                }
                store.markAllDelivered(fromNodeId)
            }
            is ChannelListRequest -> {
                send(envelope(ChannelListItems(store.channels())))
            }
            is MsgHistoryRequest -> {
                send(envelope(MsgHistoryItems(m.channelId, store.channelMessages(m.channelId, m.limit))))
            }
            is FileOffer -> handleFileOffer(fromNodeId, m)
            is FileChunk -> handleFileChunk(fromNodeId, m)
            is FileDone -> handleFileDone(fromNodeId, m)
            is FileAbort -> handleFileAbort(fromNodeId, m)
            is CallOffer, is CallAccept, is CallReject, is CallHangup, is CallAudio,
            is VideoStart, is VideoFrame, is VideoStop,
            -> handleCall(fromNodeId, m)
            else -> Unit
        }
    }

    // --- файлы ---

    private suspend fun DefaultWebSocketServerSession.handleFileOffer(fromNodeId: String, offer: FileOffer) {
        val record = withContext(Dispatchers.IO) {
            store.beginIncomingFile(offer.fileId, offer.fileName, offer.size, offer.sha256, offer.channelId, offer.authorNodeId)
        }
        events.onFileTransfer(record)
        // авто-приём; offset позволяет докачать прерванную передачу
        send(envelope(FileAccept(offer.fileId, record.receivedBytes)))
        if (offer.channelId != null) {
            broadcastToConnected(offer, exceptNodeId = fromNodeId)
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleFileChunk(fromNodeId: String, chunk: FileChunk) {
        val received = withContext(Dispatchers.IO) { store.appendChunk(chunk.fileId, chunk.data) }
        events.onFileTransferProgress(chunk.fileId, received)
        val record = store.fileRecord(chunk.fileId)
        if (record?.channelId != null) {
            broadcastToConnected(chunk, exceptNodeId = fromNodeId)
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleFileDone(fromNodeId: String, done: FileDone) {
        withContext(Dispatchers.IO) { store.completeFile(done.fileId) }
        events.onFileTransferFinished(done.fileId)
        val record = store.fileRecord(done.fileId)
        if (record?.channelId != null) {
            broadcastToConnected(done, exceptNodeId = fromNodeId)
        }
    }

    private suspend fun DefaultWebSocketServerSession.handleFileAbort(fromNodeId: String, abort: FileAbort) {
        withContext(Dispatchers.IO) { store.abortFile(abort.fileId, abort.reason) }
        events.onFileTransferFinished(abort.fileId)
        val record = store.fileRecord(abort.fileId)
        if (record?.channelId != null) {
            broadcastToConnected(abort, exceptNodeId = fromNodeId)
        }
    }

    // --- звонки ---

    /**
     * Маршрутизирует сообщение звонка: адресованное нашему узлу — событием
     * в UI, чужое — пересылкой целевому узлу (транзит через наш узел).
     */
    private suspend fun DefaultWebSocketServerSession.handleCall(fromNodeId: String, message: Message) {
        val to = when (message) {
            is CallOffer -> message.to
            is CallAccept -> message.to
            is CallReject -> message.to
            is CallHangup -> message.to
            is CallAudio -> message.to
            is VideoStart -> message.to
            is VideoFrame -> message.to
            is VideoStop -> message.to
            else -> return
        }
        if (to == config.nodeId) {
            when (message) {
                is CallOffer -> events.onIncomingCall(message)
                is CallAccept -> events.onCallAccepted(message)
                is CallReject -> events.onCallRejected(message)
                is CallHangup -> events.onCallHangup(message)
                is CallAudio -> events.onCallAudio(message)
                is VideoStart -> events.onVideoStart(message)
                is VideoFrame -> events.onVideoFrame(message)
                is VideoStop -> events.onVideoStop(message)
                else -> Unit
            }
        } else {
            sendTo(to, message)
        }
    }

    /** Есть ли у нас активная входящая сессия от [nodeId] (нужно для звонков). */
    fun isConnected(nodeId: String): Boolean = sessions.containsKey(nodeId)

    private fun decide(hello: Hello): Decision {
        if (hello.protocolVersion != PROTOCOL_VERSION) {
            return Decision.Denied("Несовместимая версия протокола: ${hello.protocolVersion}")
        }
        return when (store.peerStatus(hello.nodeId)) {
            PeerStatus.Blocked -> Decision.Denied("Вы заблокированы")
            PeerStatus.Accepted -> Decision.Accepted
            null, PeerStatus.Pending -> Decision.Pending
        }
    }

    private suspend fun DefaultWebSocketServerSession.receiveHello(): Hello? {
        val frame = incoming.receiveCatching().getOrNull() ?: return null
        if (frame !is Frame.Text) return null
        return runCatching {
            val envelope = DevChatsJson.decodeFromString(Envelope.serializer(), frame.readText())
            envelope.payload as? Hello
        }.getOrNull()
    }

    /** Отправляет сообщение пиру по входящей сессии (если пир подключён к нам). */
    suspend fun sendTo(nodeId: String, message: Message): Boolean {
        val session = sessions[nodeId] ?: return false
        return try {
            session.send(envelope(message))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Рассылает сообщение всем подключённым узлам, кроме [exceptNodeId]. */
    suspend fun broadcastToConnected(message: Message, exceptNodeId: String? = null) {
        for ((id, session) in sessions) {
            if (id != exceptNodeId) {
                try {
                    session.send(envelope(message))
                } catch (_: Exception) {
                    // сессия уже закрыта — пропускаем
                }
            }
        }
    }

    private fun ackEnvelope(accepted: Boolean, reason: String? = null): Frame.Text = envelope(
        HelloAck(
            nodeId = config.nodeId,
            displayName = config.displayName,
            accepted = accepted,
            reason = reason,
        )
    )

    private fun envelope(message: Message): Frame.Text = Frame.Text(
        DevChatsJson.encodeToString(
            Envelope.serializer(),
            Envelope(id = UUID.randomUUID().toString(), payload = message),
        )
    )

    private suspend fun broadcastPresence(nodeId: String, online: Boolean) {
        val message = Frame.Text(
            DevChatsJson.encodeToString(
                Envelope.serializer(),
                Envelope(id = UUID.randomUUID().toString(), payload = Presence(nodeId = nodeId, online = online)),
            )
        )
        for ((otherId, session) in sessions) {
            if (otherId != nodeId) {
                try {
                    session.send(message)
                } catch (_: Exception) {
                    // сессия уже закрыта — пропускаем
                }
            }
        }
    }
}
