package devchats.server

import devchats.protocol.CallOffer
import devchats.protocol.ChannelListItems
import devchats.protocol.DevChatsJson
import devchats.protocol.DmSend
import devchats.protocol.Envelope
import devchats.protocol.FileAccept
import devchats.protocol.FileChunk
import devchats.protocol.FileDone
import devchats.protocol.FileOffer
import devchats.protocol.Hello
import devchats.protocol.HelloAck
import devchats.protocol.MailboxSync
import devchats.protocol.MessageInfo
import devchats.protocol.MsgHistoryItems
import devchats.protocol.MsgHistoryRequest
import devchats.protocol.MsgSend
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private object NoopEvents : NodeEvents

class DevChatsServerTest {

    private fun newStore(): NodeStore {
        val dir = createTempDirectory("devchats-server-test")
        return NodeStore(dir.resolve("test.db"))
    }

    private fun frame(envelope: Envelope): Frame.Text = Frame.Text(
        DevChatsJson.encodeToString(Envelope.serializer(), envelope)
    )

    private suspend fun DefaultClientWebSocketSession.receiveEnvelope(): Envelope? {
        val frame = incoming.receiveCatching().getOrNull() as? Frame.Text ?: return null
        return runCatching {
            DevChatsJson.decodeFromString(Envelope.serializer(), frame.readText())
        }.getOrNull()
    }

    private fun server(nodeId: String, store: NodeStore, events: NodeEvents = NoopEvents) =
        DevChatsServer(NodeConfig(nodeId = nodeId, displayName = "Боб", port = 0), store, events)

    // --- HTTP ---

    @Test
    fun healthReturnsOk() = testApplication {
        application {
            server("node-b", newStore()).module(this)
        }
        val client = createClient {
            install(ContentNegotiation) { json(DevChatsJson) }
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val health = response.body<HealthResponse>()
        assertEquals("ok", health.status)
        assertEquals("node-b", health.nodeId)
        assertEquals(APP_VERSION, health.appVersion)
    }

    @Test
    fun unknownRouteReturnsNotFound() = testApplication {
        application {
            server("node-b", newStore()).module(this)
        }

        val response = client.get("/no-such-route")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    /** Проверяет реальный запуск Netty-движка: bind, HTTP-ответ, остановку. */
    @Test
    fun embeddedServerStartsAndAnswersOnRealPort() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val store = newStore()
        val server = DevChatsServer(NodeConfig(nodeId = "node-1", displayName = "Тест", port = port), store)
        server.start()
        try {
            val connection = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
            assertEquals(200, connection.responseCode)
            val body = connection.inputStream.bufferedReader().readText()
            assertTrue(body.contains("\"status\":\"ok\""), body)
            assertTrue(body.contains("\"nodeId\":\"node-1\""), body)
        } finally {
            server.stop()
        }
    }

    /** Занятый порт не мешает запуску: сервер берёт свободный. */
    @Test
    fun fallsBackToFreePortWhenPortIsBusy() = runBlocking {
        val occupied = ServerSocket(0)
        val store = newStore()
        val server = DevChatsServer(NodeConfig(nodeId = "node-1", displayName = "Тест", port = occupied.localPort), store)
        server.start()
        try {
            val bound = server.boundPort()
            assertTrue(bound != occupied.localPort, "ожидали другой порт, получили $bound")
            val connection = URI("http://127.0.0.1:$bound/health").toURL().openConnection() as HttpURLConnection
            assertEquals(200, connection.responseCode)
        } finally {
            occupied.close()
            server.stop()
        }
    }

    // --- WebSocket-рукопожатие ---

    @Test
    fun acceptedPeerReceivesPositiveAck() = testApplication {
        val store = newStore()
        store.upsertPeer(Peer("node-a", "Alice", null, PeerStatus.Accepted))
        application {
            server("node-b", store).module(this)
        }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            send(frame(Envelope(id = "1", payload = Hello("node-a", "Alice"))))
            val ack = requireNotNull(receiveEnvelope()?.payload as? HelloAck)
            assertTrue(ack.accepted, "ожидали accepted=true, получили $ack")
            assertEquals("node-b", ack.nodeId)
            assertEquals("Боб", ack.displayName)
        }
    }

    @Test
    fun unknownPeerTriggersRequestEvent() = testApplication {
        val request = CompletableDeferred<Peer>()
        val store = newStore()
        application {
            server("node-b", store, object : NodeEvents {
                override fun onConnectionRequest(peer: Peer) {
                    request.complete(peer)
                }
            }).module(this)
        }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            send(frame(Envelope(id = "1", payload = Hello("node-a", "Alice"))))
            val ack = requireNotNull(receiveEnvelope()?.payload as? HelloAck)
            assertFalse(ack.accepted)
            assertEquals("pending", ack.reason)
        }

        val peer = withTimeout(5_000) { request.await() }
        assertEquals("node-a", peer.nodeId)
        assertEquals(PeerStatus.Pending, peer.status)
    }

    @Test
    fun blockedPeerIsRejected() = testApplication {
        val store = newStore()
        store.upsertPeer(Peer("node-a", "Alice", null, PeerStatus.Blocked))
        application {
            server("node-b", store).module(this)
        }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            send(frame(Envelope(id = "1", payload = Hello("node-a", "Alice"))))
            val ack = requireNotNull(receiveEnvelope()?.payload as? HelloAck)
            assertFalse(ack.accepted)
            assertEquals("Вы заблокированы", ack.reason)
        }
    }

    @Test
    fun dmSendIsStoredAndEmitsEvent() = testApplication {
        val received = CompletableDeferred<MessageInfo>()
        val store = newStore()
        store.upsertPeer(Peer("node-a", "Alice", null, PeerStatus.Accepted))
        application {
            server("node-b", store, object : NodeEvents {
                override fun onMessageReceived(message: MessageInfo) {
                    received.complete(message)
                }
            }).module(this)
        }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            send(frame(Envelope(id = "1", payload = Hello("node-a", "Alice"))))
            requireNotNull(receiveEnvelope()?.payload as? HelloAck)
            send(frame(Envelope(id = "2", payload = DmSend("m-1", "node-b", "привет!", 100L))))
        }

        val message = withTimeout(5_000) { received.await() }
        assertEquals("m-1", message.messageId)
        assertEquals(null, message.channelId)
        assertEquals("node-a", message.authorNodeId)
        assertTrue(store.dmConversation("node-a").any { it.messageId == "m-1" })
    }

    @Test
    fun mailboxSyncReturnsOutboxAndMarksDelivered() = testApplication {
        val store = newStore()
        store.upsertPeer(Peer("node-a", "Alice", null, PeerStatus.Accepted))
        // у узла Б есть неотправленное сообщение для А
        store.insertMessage("m-out", null, "node-b", "node-a", "тебе из ящика", 100L, delivered = false)
        application {
            server("node-b", store).module(this)
        }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            send(frame(Envelope(id = "1", payload = Hello("node-a", "Alice"))))
            requireNotNull(receiveEnvelope()?.payload as? HelloAck)
            send(frame(Envelope(id = "2", payload = MailboxSync)))

            val dm = requireNotNull(receiveEnvelope()?.payload as? DmSend)
            assertEquals("m-out", dm.messageId)
            assertEquals("тебе из ящика", dm.text)
        }

        // сервер помечает сообщения доставленными после отправки — ждём завершения
        val empty = withTimeout(5_000) {
            var done = false
            while (!done) {
                done = store.pendingOutboxFor("node-a").isEmpty()
                if (!done) kotlinx.coroutines.delay(50)
            }
            true
        }
        assertTrue(empty, "после синхронизации outbox пуст")
    }

    @Test
    fun channelListAndHistoryAreServed() = testApplication {
        val store = newStore()
        store.upsertPeer(Peer("node-a", "Alice", null, PeerStatus.Accepted))
        val channel = store.addChannel("general", "text")
        store.insertMessage("c1", channel.id, "node-a", null, "первое", 100L)
        application {
            server("node-b", store).module(this)
        }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            send(frame(Envelope(id = "1", payload = Hello("node-a", "Alice"))))
            requireNotNull(receiveEnvelope()?.payload as? HelloAck)

            send(frame(Envelope(id = "2", payload = devchats.protocol.ChannelListRequest)))
            val items = requireNotNull(receiveEnvelope()?.payload as? ChannelListItems)
            assertEquals(listOf("general"), items.channels.map { it.name })

            send(frame(Envelope(id = "3", payload = MsgHistoryRequest(channel.id, limit = 200))))
            val history = requireNotNull(receiveEnvelope()?.payload as? MsgHistoryItems)
            assertEquals(channel.id, history.channelId)
            assertEquals(listOf("c1"), history.messages.map { it.messageId })
        }
    }

    @Test
    fun fileTransferIsReceivedAndVerified() = testApplication {
        val finished = CompletableDeferred<FileRecord>()
        val store = newStore()
        store.upsertPeer(Peer("node-a", "Alice", null, PeerStatus.Accepted))
        application {
            server("node-b", store, object : NodeEvents {
                override fun onFileTransferFinished(fileId: String) {
                    store.fileRecord(fileId)?.let { finished.complete(it) }
                }
            }).module(this)
        }
        val client = createClient { install(WebSockets) }

        val content = "file content over ws".toByteArray()
        val sha = sha256Hex(content)

        client.webSocket("/ws") {
            send(frame(Envelope(id = "1", payload = Hello("node-a", "Alice"))))
            requireNotNull(receiveEnvelope()?.payload as? HelloAck)

            send(frame(Envelope(id = "2", payload = FileOffer("f-ws", "a.txt", content.size.toLong(), sha, null, "node-a"))))
            requireNotNull(receiveEnvelope()?.payload as? FileAccept)

            content.toList().chunked(4).forEachIndexed { index, part ->
                send(frame(Envelope(id = "3-$index", payload = FileChunk("f-ws", index, part.toByteArray()))))
            }
            send(frame(Envelope(id = "4", payload = FileDone("f-ws"))))
        }

        val record = withTimeout(5_000) { finished.await() }
        assertEquals(FileStatus.Complete, record.status)
        assertEquals(content.size.toLong(), record.receivedBytes)
        assertEquals(String(content), java.nio.file.Files.readString(java.nio.file.Paths.get(record.localPath)))
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun incomingCallEmitsEvent() = testApplication {
        val incoming = CompletableDeferred<CallOffer>()
        val store = newStore()
        store.upsertPeer(Peer("node-a", "Alice", null, PeerStatus.Accepted))
        application {
            server("node-b", store, object : NodeEvents {
                override fun onIncomingCall(call: CallOffer) {
                    incoming.complete(call)
                }
            }).module(this)
        }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            send(frame(Envelope(id = "1", payload = Hello("node-a", "Alice"))))
            requireNotNull(receiveEnvelope()?.payload as? HelloAck)
            send(frame(Envelope(id = "2", payload = CallOffer("call-1", "node-b", "node-a", "Alice"))))
        }

        val call = withTimeout(5_000) { incoming.await() }
        assertEquals("call-1", call.callId)
        assertEquals("node-b", call.to)
        assertEquals("node-a", call.fromNodeId)
        assertEquals("Alice", call.fromName)
    }

    @Test
    fun wrongProtocolVersionIsRejected() = testApplication {
        val store = newStore()
        store.upsertPeer(Peer("node-a", "Alice", null, PeerStatus.Accepted))
        application {
            server("node-b", store).module(this)
        }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            send(frame(Envelope(id = "1", payload = Hello("node-a", "Alice", protocolVersion = 999))))
            val ack = requireNotNull(receiveEnvelope()?.payload as? HelloAck)
            assertFalse(ack.accepted)
            assertTrue(ack.reason?.contains("Несовместимая") == true, "причина: ${ack.reason}")
        }
    }
}
