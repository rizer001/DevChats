package devchats.desktop

import devchats.desktop.audio.OpusCodec
import devchats.desktop.media.VideoCodec
import devchats.desktop.media.VideoSource
import devchats.protocol.CallAccept
import devchats.protocol.CallAudio
import devchats.protocol.CallHangup
import devchats.protocol.CallOffer
import devchats.protocol.CallReject
import devchats.protocol.Message
import devchats.protocol.VIDEO_KIND_CAMERA
import devchats.protocol.VideoFrame
import devchats.protocol.VideoStart
import devchats.protocol.VideoStop
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Тест машины состояний звонка с in-memory маршрутизатором:
 * сеть заменена на два почтовых ящика, кодек настоящий.
 */
class CallManagerTest {

    @Test
    fun fullCallLifecycle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val toB = Channel<Message>(64) // от A к B
        val toA = Channel<Message>(64) // от B к A

        val a = CallManager(scope, { "node-a" }, { "Alice" }) { peer, msg -> toB.trySend(msg); true }
        val b = CallManager(scope, { "node-b" }, { "Bob" }) { peer, msg -> toA.trySend(msg); true }

        try {
            // A звонит B
            a.startCall("node-b", "Bob")
            assertEquals(CallPhase.Outgoing, a.phase)

            val offer = withTimeout(5_000) { toB.receive() as CallOffer }
            assertEquals("node-b", offer.to)
            assertEquals("Alice", offer.fromName)

            b.onOffer(offer.callId, offer.fromNodeId, offer.fromName)
            assertEquals(CallPhase.Incoming, b.phase)

            // B принимает
            b.acceptIncoming()
            val accept = withTimeout(5_000) { toA.receive() as CallAccept }
            a.onAccept(accept.callId, accept.fromNodeId)
            assertEquals(CallPhase.Active, a.phase)
            assertEquals(CallPhase.Active, b.phase)
            assertEquals(offer.callId, b.callId)

            // голос: A шлёт кадр, B декодирует (динамика в тесте нет — молча пропускает)
            val codec = OpusCodec()
            val pcm = ShortArray(codec.frameSize) { (sin(2 * PI * 440.0 * it / 48_000.0) * 8000).toInt().toShort() }
            val packet = codec.encode(pcm)
            b.onAudio(b.callId!!, packet)
            assertEquals(CallPhase.Active, b.phase)

            // B завершает звонок
            b.hangup()
            val hangup = withTimeout(5_000) { toA.receive() as CallHangup }
            a.onHangup(hangup.callId, hangup.fromNodeId)

            assertEquals(CallPhase.Idle, a.phase)
            assertEquals(CallPhase.Idle, b.phase)
            assertEquals(null, a.callId)
            assertEquals(null, b.callId)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun busyPeerAutoRejects() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val toA = Channel<Message>(64)
        val a = CallManager(scope, { "node-a" }, { "Alice" }) { peer, msg -> toA.trySend(msg); true }
        val b = CallManager(scope, { "node-b" }, { "Bob" }) { peer, msg -> toA.trySend(msg); true }

        try {
            // B уже в звонке (входящий)
            b.onOffer("call-1", "node-x", "Xavier")
            assertEquals(CallPhase.Incoming, b.phase)

            // A пытается дозвониться до B
            a.startCall("node-b", "Bob")
            val offer = withTimeout(5_000) { toA.receive() as CallOffer }
            b.onOffer(offer.callId, offer.fromNodeId, offer.fromName)

            // B автоматически отклонил — A получил «занят»
            val reject = withTimeout(5_000) { toA.receive() as CallReject }
            assertEquals("занят", reject.reason)
            a.onReject(reject.callId, reject.fromNodeId, reject.reason)
            assertEquals(CallPhase.Idle, a.phase)
            assertTrue(a.notice?.contains("занят") == true)
            // исходный звонок B не тронут
            assertEquals(CallPhase.Incoming, b.phase)
            assertEquals("call-1", b.callId)
        } finally {
            scope.cancel()
        }
    }

    /** Фейковый источник видео: выдаёт синтетический кадр. */
    private class FakeVideoSource : VideoSource {
        var captures = 0
            private set
        override fun capture(): BufferedImage? {
            captures++
            return VideoCodec.blankFrame(320, 180)
        }
    }

    @Test
    fun videoShareLifecycle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val toB = Channel<Message>(64)
        val toA = Channel<Message>(64)
        val source = FakeVideoSource()

        val a = CallManager(scope, { "node-a" }, { "Alice" }, videoSourceFactory = { source }) { peer, msg -> toB.trySend(msg); true }
        val b = CallManager(scope, { "node-b" }, { "Bob" }, videoSourceFactory = { source }) { peer, msg -> toA.trySend(msg); true }

        try {
            // A звонит B, B принимает
            a.startCall("node-b", "Bob")
            val offer = withTimeout(5_000) { toB.receive() as CallOffer }
            b.onOffer(offer.callId, offer.fromNodeId, offer.fromName)
            b.acceptIncoming()
            val accept = withTimeout(5_000) { toA.receive() as CallAccept }
            a.onAccept(accept.callId, accept.fromNodeId)
            assertEquals(CallPhase.Active, a.phase)
            assertEquals(CallPhase.Active, b.phase)

            // A включает камеру
            a.toggleCamera()
            assertEquals(VIDEO_KIND_CAMERA, a.videoKind)
            val start = withTimeout(5_000) { toB.receive() as VideoStart }
            assertEquals(VIDEO_KIND_CAMERA, start.kind)
            assertEquals(320, start.width)
            assertEquals(180, start.height)

            b.onVideoStart(start.callId, start.fromNodeId, start.kind, start.width, start.height)
            assertEquals(VIDEO_KIND_CAMERA, b.remoteVideoKind)

            // кадры летят по сети и декодируются
            val frame = withTimeout(5_000) { toB.receive() as VideoFrame }
            assertEquals(VIDEO_KIND_CAMERA, frame.kind)
            assertTrue(source.captures >= 2, "источник должен был захватить кадры: ${source.captures}")
            b.onVideoFrame(frame.callId, frame.kind, frame.data)
            withTimeout(5_000) {
                while (b.remoteVideoFrame == null) delay(20)
            }
            assertNotNull(b.remoteVideoFrame)
            assertEquals(VIDEO_KIND_CAMERA, b.remoteVideoKind)

            // A выключает камеру — B очищает видео
            a.toggleCamera()
            val stop = withTimeout(5_000) { toB.receive() as VideoStop }
            assertEquals(VIDEO_KIND_CAMERA, stop.kind)
            assertEquals(null, a.videoKind)
            b.onVideoStop(stop.callId, stop.kind)
            assertEquals(null, b.remoteVideoKind)
            assertEquals(null, b.remoteVideoFrame)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun rejectEndsCall() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val toA = Channel<Message>(64)
        val a = CallManager(scope, { "node-a" }, { "Alice" }) { peer, msg -> toA.trySend(msg); true }

        try {
            a.startCall("node-b", "Bob")
            val offer = withTimeout(5_000) { toA.receive() as CallOffer }
            val reject = CallReject(offer.callId, "node-a", "node-b", "отклонён")
            a.onReject(reject.callId, reject.fromNodeId, reject.reason)
            assertEquals(CallPhase.Idle, a.phase)
            assertTrue(a.notice == "Звонок отклонён")
        } finally {
            scope.cancel()
        }
    }
}
