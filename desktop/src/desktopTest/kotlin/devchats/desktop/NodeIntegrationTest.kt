package devchats.desktop

import devchats.client.NodeClient
import devchats.client.NodeConnectionState
import devchats.client.RemoteNode
import devchats.protocol.CallAccept
import devchats.protocol.CallHangup
import devchats.protocol.CallOffer
import devchats.protocol.DmSend
import devchats.protocol.VideoStart
import devchats.protocol.Envelope
import devchats.protocol.FileAccept
import devchats.protocol.FileChunk
import devchats.protocol.FileDone
import devchats.protocol.FileOffer
import devchats.protocol.Hello
import devchats.protocol.MsgSend
import devchats.server.FileRecord
import devchats.server.FileStatus
import devchats.server.NodeEvents
import java.nio.file.Files
import java.nio.file.Paths
import devchats.server.DevChatsServer
import devchats.server.NodeStore
import devchats.server.Peer
import devchats.server.PeerStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.awt.image.BufferedImage
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Интеграционные тесты: настоящий Netty-сервер на свободном порту
 * и настоящий клиент по TCP + WebSocket.
 */
class NodeIntegrationTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun newStore(): NodeStore {
        val dir = createTempDirectory("devchats-int")
        return NodeStore(dir.resolve("test.db"))
    }

    @Test
    fun clientConnectsToAcceptedPeer() = runBlocking {
        val store = newStore()
        val serverConfig = store.loadOrCreateConfig().copy(port = freePort())
        store.upsertPeer(Peer("node-a", "Alice", null, PeerStatus.Accepted))

        val server = DevChatsServer(serverConfig, store)
        server.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = NodeClient(HttpClient(CIO) { install(WebSockets) }, scope)
        try {
            val remote = client.connect("127.0.0.1", serverConfig.port, Hello("node-a", "Alice"))
            val state = withTimeout(10_000) {
                remote.state.first { it !is NodeConnectionState.Connecting }
            }
            assertTrue(state is NodeConnectionState.Connected, "ожидали Connected, получили $state")
            assertEquals(serverConfig.nodeId, (state as NodeConnectionState.Connected).nodeId)
            remote.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun unknownPeerWaitsForApprovalThenConnects() = runBlocking {
        val store = newStore()
        val serverConfig = store.loadOrCreateConfig().copy(port = freePort())

        val server = DevChatsServer(serverConfig, store)
        server.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = NodeClient(HttpClient(CIO) { install(WebSockets) }, scope)
        try {
            val remote = client.connect("127.0.0.1", serverConfig.port, Hello("node-a", "Alice"))

            // сначала — ожидание подтверждения
            val waiting = withTimeout(10_000) {
                remote.state.first { it is NodeConnectionState.WaitingForApproval }
            }
            assertTrue(waiting is NodeConnectionState.WaitingForApproval)

            // владелец узла принимает запрос — клиент переходит в Connected
            server.accept("node-a")
            val connected = withTimeout(10_000) {
                remote.state.first { it is NodeConnectionState.Connected }
            }
            assertTrue(connected is NodeConnectionState.Connected)
            assertEquals(serverConfig.nodeId, (connected as NodeConnectionState.Connected).nodeId)
            remote.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun outboxSyncDeliversDmToPeer() = runBlocking {
        // Узел А: сервер + outbox для Б. Узел Б: сервер, принявший А.
        val storeA = newStore()
        val configA = storeA.loadOrCreateConfig()
        val storeB = newStore()
        val configB = storeB.loadOrCreateConfig().copy(port = freePort())
        storeB.upsertPeer(Peer(configA.nodeId, "Alice", null, PeerStatus.Accepted))

        // А не смог доставить DM (Б был офлайн) — сообщение в outbox
        storeA.insertMessage("m-offline", null, configA.nodeId, configB.nodeId, "офлайн-сообщение", 1_000L, delivered = false)

        val serverB = DevChatsServer(configB, storeB)
        serverB.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = NodeClient(HttpClient(CIO) { install(WebSockets) }, scope)
        try {
            val remote = client.connect("127.0.0.1", configB.port, Hello(configA.nodeId, "Alice"))
            withTimeout(10_000) { remote.state.first { it is NodeConnectionState.Connected } }

            // клиент А доставляет outbox и синхронизирует почтовый ящик
            syncOutbox(remote, storeA, configB.nodeId)

            // сообщение должно оказаться в БД узла Б
            val delivered = withTimeout(10_000) {
                var found = false
                while (!found) {
                    found = storeB.dmConversation(configA.nodeId).any { it.messageId == "m-offline" }
                    if (!found) kotlinx.coroutines.delay(50)
                }
                true
            }
            assertTrue(delivered, "узел Б должен получить сообщение из outbox узла А")
            remote.close()
        } finally {
            serverB.stop()
        }
    }

    @Test
    fun channelMessageIsRelayedToSecondClient() = runBlocking {
        val storeB = newStore()
        val configB = storeB.loadOrCreateConfig().copy(port = freePort())
        storeB.upsertPeer(Peer("node-a", "Alice", null, PeerStatus.Accepted))
        storeB.upsertPeer(Peer("node-c", "Carol", null, PeerStatus.Accepted))
        val channel = storeB.addChannel("general", "text")

        val serverB = DevChatsServer(configB, storeB)
        serverB.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = NodeClient(HttpClient(CIO) { install(WebSockets) }, scope)
        try {
            val a = client.connect("127.0.0.1", configB.port, Hello("node-a", "Alice"))
            val c = client.connect("127.0.0.1", configB.port, Hello("node-c", "Carol"))
            withTimeout(10_000) { a.state.first { it is NodeConnectionState.Connected } }
            withTimeout(10_000) { c.state.first { it is NodeConnectionState.Connected } }

            // А шлёт сообщение в канал — Б должен ретранслировать его узлу С
            a.send(MsgSend("m-ch", channel.id, "node-a", "всем привет!", 2_000L))

            val relayed = withTimeout(10_000) {
                c.messages.first { envelope ->
                    (envelope.payload as? MsgSend)?.messageId == "m-ch"
                }
            }
            assertEquals("всем привет!", (relayed.payload as MsgSend).text)

            // и сохранить в своей БД
            assertTrue(storeB.channelMessages(channel.id).any { it.messageId == "m-ch" })
            a.close()
            c.close()
        } finally {
            serverB.stop()
        }
    }

    @Test
    fun fileIsTransferredBetweenNodes() = runBlocking {
        val storeA = newStore()
        val configA = storeA.loadOrCreateConfig()
        val storeB = newStore()
        val configB = storeB.loadOrCreateConfig().copy(port = freePort())
        storeB.upsertPeer(Peer(configA.nodeId, "Alice", null, PeerStatus.Accepted))

        // исходный файл у узла А
        val sourceDir = createTempDirectory("devchats-src")
        val sourceFile = sourceDir.resolve("note.txt")
        val content = "привет, это файл через сеть!".toByteArray()
        Files.write(sourceFile, content)
        val sha = sha256Hex(content)

        val serverB = DevChatsServer(configB, storeB)
        serverB.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = NodeClient(HttpClient(CIO) { install(WebSockets) }, scope)
        try {
            val remote = client.connect("127.0.0.1", configB.port, Hello(configA.nodeId, "Alice"))
            withTimeout(10_000) { remote.state.first { it is NodeConnectionState.Connected } }

            val fileId = "f-int"
            remote.send(FileOffer(fileId, "note.txt", content.size.toLong(), sha, null, configA.nodeId))
            withTimeout(10_000) { remote.messages.first { it.payload is FileAccept } }

            // отправляем чанками по 3 байта
            var index = 0
            for (offset in content.indices step 3) {
                val end = minOf(offset + 3, content.size)
                remote.send(FileChunk(fileId, index, content.copyOfRange(offset, end)))
                index++
            }
            remote.send(FileDone(fileId))

            // узел Б получил полный файл
            val record = withTimeout(10_000) {
                var current: FileRecord? = null
                while (current?.status != FileStatus.Complete) {
                    current = storeB.fileRecord(fileId)
                    if (current?.status == FileStatus.Aborted) break
                    kotlinx.coroutines.delay(50)
                }
                requireNotNull(current)
            }
            assertEquals(FileStatus.Complete, record.status)
            assertEquals(String(content), Files.readString(Paths.get(record.localPath)))
            remote.close()
        } finally {
            serverB.stop()
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    // --- звонки ---

    @Test
    fun callOfferIsRelayedThroughHubBetweenTwoPeers() = runBlocking {
        // A и C подключены к узлу B; A звонит C — B транзитом передаёт звонок.
        val storeB = newStore()
        val configB = storeB.loadOrCreateConfig().copy(port = freePort())
        storeB.upsertPeer(Peer("node-a", "Alice", null, PeerStatus.Accepted))
        storeB.upsertPeer(Peer("node-c", "Carol", null, PeerStatus.Accepted))

        val serverB = DevChatsServer(configB, storeB)
        serverB.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = NodeClient(HttpClient(CIO) { install(WebSockets) }, scope)
        try {
            val a = client.connect("127.0.0.1", configB.port, Hello("node-a", "Alice"))
            val c = client.connect("127.0.0.1", configB.port, Hello("node-c", "Carol"))
            withTimeout(10_000) { a.state.first { it is NodeConnectionState.Connected } }
            withTimeout(10_000) { c.state.first { it is NodeConnectionState.Connected } }

            // A звонит C через узел B
            a.send(CallOffer("call-relay", "node-c", "node-a", "Alice"))
            val offer = withTimeout(10_000) { c.messages.first { it.payload is CallOffer } }
            assertEquals("node-a", (offer.payload as CallOffer).fromNodeId)

            // C отвечает — ответ идёт обратно через B к A
            c.send(CallAccept("call-relay", "node-a", "node-c"))
            val accept = withTimeout(10_000) { a.messages.first { it.payload is CallAccept } }
            assertEquals("node-c", (accept.payload as CallAccept).fromNodeId)

            // и видео-кадр транзитом через узел B
            a.send(VideoStart("call-relay", "node-c", "node-a", devchats.protocol.VIDEO_KIND_SCREEN, 640, 360))
            val vstart = withTimeout(10_000) { c.messages.first { it.payload is devchats.protocol.VideoStart } }
            assertEquals(640, (vstart.payload as devchats.protocol.VideoStart).width)

            a.close()
            c.close()
        } finally {
            serverB.stop()
        }
    }

    @Test
    fun videoFramesFlowOverTcp() = runBlocking {
        // Видео от A к B через реальное TCP-соединение: video.start → кадры → video.stop.
        val storeA = newStore()
        val configA = storeA.loadOrCreateConfig()
        val storeB = newStore()
        val configB = storeB.loadOrCreateConfig().copy(port = freePort())
        storeB.upsertPeer(Peer(configA.nodeId, "Alice", null, PeerStatus.Accepted))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = NodeClient(HttpClient(CIO) { install(WebSockets) }, scope)

        lateinit var remoteA: RemoteNode
        var managerB: CallManager? = null
        val fakeSource = object : devchats.desktop.media.VideoSource {
            override fun capture(): BufferedImage? = devchats.desktop.media.VideoCodec.blankFrame(320, 180)
        }
        val managerA = CallManager(
            scope,
            { configA.nodeId },
            { "Alice" },
            videoSourceFactory = { fakeSource },
        ) { peer, message ->
            remoteA.send(message)
            true
        }

        val eventsB = object : NodeEvents {
            override fun onIncomingCall(call: CallOffer) {
                managerB?.onOffer(call.callId, call.fromNodeId, call.fromName)
            }
            override fun onCallAccepted(call: CallAccept) {
                managerB?.onAccept(call.callId, call.fromNodeId)
            }
            override fun onCallHangup(call: CallHangup) {
                managerB?.onHangup(call.callId, call.fromNodeId)
            }
            override fun onVideoStart(video: devchats.protocol.VideoStart) {
                managerB?.onVideoStart(video.callId, video.fromNodeId, video.kind, video.width, video.height)
            }
            override fun onVideoFrame(video: devchats.protocol.VideoFrame) {
                managerB?.onVideoFrame(video.callId, video.kind, video.data)
            }
            override fun onVideoStop(video: devchats.protocol.VideoStop) {
                managerB?.onVideoStop(video.callId, video.kind)
            }
        }
        val serverB = DevChatsServer(configB, storeB, eventsB)
        managerB = CallManager(
            scope,
            { configB.nodeId },
            { "Боб" },
            videoSourceFactory = { fakeSource },
        ) { peer, message ->
            serverB.sendTo(peer, message)
        }

        try {
            serverB.start()
            remoteA = client.connect("127.0.0.1", configB.port, Hello(configA.nodeId, "Alice"))
            withTimeout(10_000) { remoteA.state.first { it is NodeConnectionState.Connected } }

            val aJob = scope.launch {
                remoteA.messages.collect { envelope ->
                    when (val p = envelope.payload) {
                        is CallOffer -> managerA.onOffer(p.callId, p.fromNodeId, p.fromName)
                        is CallAccept -> managerA.onAccept(p.callId, p.fromNodeId)
                        is CallHangup -> managerA.onHangup(p.callId, p.fromNodeId)
                        else -> Unit
                    }
                }
            }

            // A звонит B, B принимает
            managerA.startCall(configB.nodeId, "Боб")
            withTimeout(10_000) { while (managerB.phase != CallPhase.Incoming) kotlinx.coroutines.delay(20) }
            managerB.acceptIncoming()
            withTimeout(10_000) { while (managerA.phase != CallPhase.Active) kotlinx.coroutines.delay(20) }
            assertEquals(CallPhase.Active, managerB.phase)

            // A показывает экран — B получает video.start и кадры по сети
            managerA.toggleScreenShare()
            withTimeout(10_000) {
                while (managerB.remoteVideoKind != devchats.protocol.VIDEO_KIND_SCREEN) kotlinx.coroutines.delay(20)
            }
            assertEquals(devchats.protocol.VIDEO_KIND_SCREEN, managerB.remoteVideoKind)
            withTimeout(10_000) {
                while (managerB.remoteVideoFrame == null) kotlinx.coroutines.delay(20)
            }
            assertNotNull(managerB.remoteVideoFrame)

            // A выключает — B очищает
            managerA.toggleScreenShare()
            withTimeout(10_000) {
                while (managerB.remoteVideoKind != null) kotlinx.coroutines.delay(20)
            }
            assertEquals(null, managerB.remoteVideoKind)

            aJob.cancel()
            remoteA.close()
        } finally {
            serverB.stop()
            scope.cancel()
        }
    }

    @Test
    fun fullCallFlowOverTcpWithCallManagers() = runBlocking {
        // Два настоящих узла по TCP: A соединён с B, у каждого свой CallManager.
        val storeA = newStore()
        val configA = storeA.loadOrCreateConfig()
        val storeB = newStore()
        val configB = storeB.loadOrCreateConfig().copy(port = freePort())
        storeB.upsertPeer(Peer(configA.nodeId, "Alice", null, PeerStatus.Accepted))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = NodeClient(HttpClient(CIO) { install(WebSockets) }, scope)

        lateinit var remoteA: RemoteNode
        var managerB: CallManager? = null
        val managerA = CallManager(
            scope,
            { configA.nodeId },
            { "Alice" },
        ) { peer, message ->
            // A звонит B по исходящему соединению
            remoteA.send(message)
            true
        }

        // B-сторона: звонки от A приходят в сервер B событиями, ответы — sendTo
        val eventsB = object : NodeEvents {
            override fun onIncomingCall(call: CallOffer) {
                managerB?.onOffer(call.callId, call.fromNodeId, call.fromName)
            }
            override fun onCallAccepted(call: CallAccept) {
                managerB?.onAccept(call.callId, call.fromNodeId)
            }
            override fun onCallRejected(call: devchats.protocol.CallReject) {
                managerB?.onReject(call.callId, call.fromNodeId, call.reason)
            }
            override fun onCallHangup(call: CallHangup) {
                managerB?.onHangup(call.callId, call.fromNodeId)
            }
            override fun onCallAudio(call: devchats.protocol.CallAudio) {
                managerB?.onAudio(call.callId, call.data)
            }
        }
        val serverB = DevChatsServer(configB, storeB, eventsB)
        managerB = CallManager(
            scope,
            { configB.nodeId },
            { "Боб" },
        ) { peer, message ->
            // B отвечает A по входящей сессии (A подключён к B)
            serverB.sendTo(peer, message)
        }

        try {
            serverB.start()
            remoteA = client.connect("127.0.0.1", configB.port, Hello(configA.nodeId, "Alice"))
            withTimeout(10_000) { remoteA.state.first { it is NodeConnectionState.Connected } }

            // A-сторона: ответы от B приходят по исходящему соединению
            val aJob = scope.launch {
                remoteA.messages.collect { envelope ->
                    when (val p = envelope.payload) {
                        is CallOffer -> managerA.onOffer(p.callId, p.fromNodeId, p.fromName)
                        is CallAccept -> managerA.onAccept(p.callId, p.fromNodeId)
                        is CallHangup -> managerA.onHangup(p.callId, p.fromNodeId)
                        else -> Unit
                    }
                }
            }

            // A звонит B
            managerA.startCall(configB.nodeId, "Боб")
            val incoming = withTimeout(10_000) {
                while (managerB.phase != CallPhase.Incoming) kotlinx.coroutines.delay(20)
                managerB.phase
            }
            assertEquals(CallPhase.Incoming, incoming)

            // B принимает — оба активны
            managerB.acceptIncoming()
            val active = withTimeout(10_000) {
                while (managerA.phase != CallPhase.Active) kotlinx.coroutines.delay(20)
                managerA.phase
            }
            assertEquals(CallPhase.Active, active)
            assertEquals(CallPhase.Active, managerB.phase)

            // B завершает звонок — A узнаёт по сети
            managerB.hangup()
            val idle = withTimeout(10_000) {
                while (managerA.phase != CallPhase.Idle) kotlinx.coroutines.delay(20)
                managerA.phase
            }
            assertEquals(CallPhase.Idle, idle)
            assertEquals(CallPhase.Idle, managerB.phase)
            aJob.cancel()
            remoteA.close()
        } finally {
            serverB.stop()
            scope.cancel()
        }
    }

    @Test
    fun deniedPeerGetsRejected() = runBlocking {
        val store = newStore()
        val serverConfig = store.loadOrCreateConfig().copy(port = freePort())

        val server = DevChatsServer(serverConfig, store)
        server.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = NodeClient(HttpClient(CIO) { install(WebSockets) }, scope)
        try {
            val remote = client.connect("127.0.0.1", serverConfig.port, Hello("node-a", "Alice"))

            val waiting = withTimeout(10_000) {
                remote.state.first { it is NodeConnectionState.WaitingForApproval }
            }
            assertTrue(waiting is NodeConnectionState.WaitingForApproval)

            server.deny("node-a")
            val rejected = withTimeout(10_000) {
                remote.state.first { it is NodeConnectionState.Rejected }
            }
            assertTrue(rejected is NodeConnectionState.Rejected)
            assertEquals("Отклонено", (rejected as NodeConnectionState.Rejected).reason)
            remote.close()
        } finally {
            server.stop()
        }
    }
}
