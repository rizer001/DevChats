package devchats.client

import devchats.protocol.DevChatsJson
import devchats.protocol.Envelope
import devchats.protocol.Hello
import devchats.protocol.HelloAck
import devchats.protocol.Message
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Состояние исходящего подключения к удалённому узлу. */
sealed interface NodeConnectionState {
    data object Connecting : NodeConnectionState
    data class Connected(val nodeId: String, val displayName: String) : NodeConnectionState
    data object WaitingForApproval : NodeConnectionState
    data class Rejected(val reason: String) : NodeConnectionState
    data class Disconnected(val reason: String?) : NodeConnectionState
    data class Failed(val reason: String) : NodeConnectionState
}

/**
 * Клиент DevChats: устанавливает исходящие подключения к узлам по `IP:port`.
 */
class NodeClient(
    private val client: HttpClient,
    private val scope: CoroutineScope,
) {
    /** Подключается к узлу [host]:[port] и шлёт рукопожатие [identity]. */
    fun connect(host: String, port: Int, identity: Hello): RemoteNode {
        val node = RemoteNode(host, port, scope)
        scope.launch { node.run(client, identity) }
        return node
    }
}

/** Исходящее подключение к одному удалённому узлу. */
class RemoteNode internal constructor(
    val host: String,
    val port: Int,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<NodeConnectionState>(NodeConnectionState.Connecting)
    val state: StateFlow<NodeConnectionState> = _state.asStateFlow()

    /** Входящие сообщения протокола (кроме handshake), напр. [devchats.protocol.Presence]. */
    private val _messages = MutableSharedFlow<Envelope>(extraBufferCapacity = 32)
    val messages: SharedFlow<Envelope> = _messages.asSharedFlow()

    private var session: DefaultClientWebSocketSession? = null

    internal suspend fun run(client: HttpClient, identity: Hello) {
        try {
            val s = client.webSocketSession(
                method = HttpMethod.Get,
                host = host,
                port = port,
                path = "/ws",
            )
            session = s
            s.send(Frame.Text(DevChatsJson.encodeToString(Envelope.serializer(), Envelope(id = newMessageId(), payload = identity))))
            for (frame in s.incoming) {
                if (frame is Frame.Text) {
                    val envelope = runCatching {
                        DevChatsJson.decodeFromString(Envelope.serializer(), frame.readText())
                    }.getOrNull() ?: continue
                    handle(envelope)
                }
            }
            _state.update { current ->
                when (current) {
                    is NodeConnectionState.Connected,
                    is NodeConnectionState.WaitingForApproval -> NodeConnectionState.Disconnected(null)
                    else -> current // Rejected/Failed/Disconnected остаются как есть
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { current ->
                if (current == NodeConnectionState.Connecting) {
                    NodeConnectionState.Failed(e.message ?: e.toString())
                } else {
                    current
                }
            }
        } finally {
            session = null
        }
    }

    private suspend fun handle(envelope: Envelope) {
        when (val payload = envelope.payload) {
            is HelloAck -> {
                _state.value = when {
                    payload.accepted -> NodeConnectionState.Connected(payload.nodeId, payload.displayName)
                    payload.reason == "pending" -> NodeConnectionState.WaitingForApproval
                    else -> NodeConnectionState.Rejected(payload.reason ?: "Отклонено")
                }
            }
            else -> _messages.tryEmit(envelope)
        }
    }

    /** Отправляет сообщение протокола (используется с M3: dm.send и т.д.). */
    suspend fun send(message: Message) {
        val s = session ?: error("Нет активного соединения с ${host}:$port")
        s.send(Frame.Text(DevChatsJson.encodeToString(Envelope.serializer(), Envelope(id = newMessageId(), payload = message))))
    }

    suspend fun close() {
        session?.close()
        session = null
        _state.value = NodeConnectionState.Disconnected("закрыто")
    }
}

private fun newMessageId(): String = UUID.randomUUID().toString()
