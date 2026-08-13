package devchats.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import devchats.desktop.audio.AudioEngine
import devchats.desktop.audio.OpusCodec
import devchats.desktop.media.VideoCodec
import devchats.desktop.media.VideoSource
import devchats.desktop.media.videoSourceFor
import devchats.protocol.CallAccept
import devchats.protocol.CallAudio
import devchats.protocol.CallHangup
import devchats.protocol.CallOffer
import devchats.protocol.CallReject
import devchats.protocol.Message
import devchats.protocol.VIDEO_KIND_CAMERA
import devchats.protocol.VIDEO_KIND_SCREEN
import devchats.protocol.VideoFrame
import devchats.protocol.VideoStart
import devchats.protocol.VideoStop
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Фаза звонка. */
enum class CallPhase { Idle, Outgoing, Incoming, Active }

/**
 * Управление голосовым звонком: состояние (фаза, собеседник, длительность),
 * Opus-кодек, захват/воспроизведение звука и поток отправки кадров.
 *
 * Сетевой транспорт полностью абстрагирован через [route] — тот же маршрут,
 * что и у личных сообщений (исходящее соединение или входящая сессия узла),
 * поэтому звонок работает там же, где работает DM.
 */
class CallManager(
    private val scope: CoroutineScope,
    private val nodeIdProvider: () -> String,
    private val displayNameProvider: () -> String,
    /** Создаёт источник видео-кадров по виду трансляции (в тестах подменяется). */
    private val videoSourceFactory: (kind: String) -> VideoSource? = ::videoSourceFor,
    private val route: suspend (peerNodeId: String, message: Message) -> Boolean,
) {
    var phase by mutableStateOf(CallPhase.Idle)
        private set
    var callId by mutableStateOf<String?>(null)
        private set
    var peerNodeId by mutableStateOf<String?>(null)
        private set
    var peerName by mutableStateOf("")
        private set
    var muted by mutableStateOf(false)
        private set
    /** Длительность активного звонка в секундах. */
    var seconds by mutableStateOf(0)
        private set
    /** Сообщение для UI («Собеседник недоступен» и т.п.); сбрасывается [clearNotice]. */
    var notice by mutableStateOf<String?>(null)
        private set

    // --- видео ---

    /** Что я транслирую: "camera" / "screen" / null. */
    var videoKind by mutableStateOf<String?>(null)
        private set
    /** Что транслирует собеседник: "camera" / "screen" / null. */
    var remoteVideoKind by mutableStateOf<String?>(null)
        private set
    /** Последний кадр собеседника (JPEG уже декодирован). */
    var remoteVideoFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Последний локальный кадр (превью PiP). */
    var localVideoFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Размер кадров собеседника (из video.start). */
    var remoteVideoWidth by mutableStateOf(0)
        private set
    var remoteVideoHeight by mutableStateOf(0)
        private set

    private val codec = OpusCodec()
    private var audio: AudioEngine? = null
    private var sendJob: Job? = null
    private var tickJob: Job? = null
    private var seq = 0
    private var startedAt = 0L

    private var videoJob: Job? = null
    private var videoSource: VideoSource? = null
    private var videoSeq = 0

    // --- действия пользователя ---

    /** Позвонить пиру [peerNodeId]. */
    fun startCall(peerNodeId: String, peerName: String) {
        if (phase != CallPhase.Idle) return
        val id = UUID.randomUUID().toString()
        this.callId = id
        this.peerNodeId = peerNodeId
        this.peerName = peerName
        phase = CallPhase.Outgoing
        scope.launch {
            val ok = route(peerNodeId, CallOffer(id, peerNodeId, nodeIdProvider(), displayNameProvider()))
            if (!ok && phase == CallPhase.Outgoing) {
                notice = "Собеседник недоступен"
                reset()
            }
        }
    }

    /** Принять входящий звонок. */
    fun acceptIncoming() {
        val id = callId ?: return
        val peer = peerNodeId ?: return
        if (phase != CallPhase.Incoming) return
        activate(peer)
        scope.launch { route(peer, CallAccept(id, peer, nodeIdProvider())) }
    }

    /** Отклонить входящий звонок. */
    fun rejectIncoming() {
        val id = callId ?: return
        val peer = peerNodeId ?: return
        if (phase != CallPhase.Incoming) return
        scope.launch { route(peer, CallReject(id, peer, nodeIdProvider(), "отклонён")) }
        reset()
    }

    /** Завершить звонок (исходящий, входящий или активный). */
    fun hangup() {
        val id = callId ?: return
        val peer = peerNodeId ?: return
        if (phase == CallPhase.Outgoing || phase == CallPhase.Active) {
            scope.launch { route(peer, CallHangup(id, peer, nodeIdProvider())) }
        }
        reset()
    }

    fun toggleMute() {
        muted = !muted
    }

    /** Включить/выключить камеру (работает только в активном звонке). */
    fun toggleCamera() {
        if (phase != CallPhase.Active) return
        if (videoKind == VIDEO_KIND_CAMERA) stopVideo() else startVideo(VIDEO_KIND_CAMERA)
    }

    /** Включить/выключить демонстрацию экрана. */
    fun toggleScreenShare() {
        if (phase != CallPhase.Active) return
        if (videoKind == VIDEO_KIND_SCREEN) stopVideo() else startVideo(VIDEO_KIND_SCREEN)
    }

    /** Остановить свою трансляцию (камеру и экран). */
    fun stopVideo() {
        val kind = videoKind ?: return
        val peer = peerNodeId ?: return
        val id = callId ?: return
        videoJob?.cancel()
        videoJob = null
        videoSource?.close()
        videoSource = null
        videoKind = null
        localVideoFrame = null
        scope.launch { route(peer, VideoStop(id, peer, nodeIdProvider(), kind)) }
    }

    fun clearNotice() {
        notice = null
    }

    /** Соединение с собеседником оборвалось — завершаем звонок. */
    fun connectionLost() {
        if (phase == CallPhase.Active || phase == CallPhase.Outgoing) {
            if (phase == CallPhase.Active) notice = "Соединение прервано"
            reset()
        }
    }

    // --- входящие события (из AppState: исходящее соединение или узел) ---

    fun onOffer(callId: String, fromNodeId: String, fromName: String?) {
        if (phase != CallPhase.Idle) {
            // заняты — автоматически отклоняем
            scope.launch { route(fromNodeId, CallReject(callId, fromNodeId, nodeIdProvider(), "занят")) }
            return
        }
        this.callId = callId
        this.peerNodeId = fromNodeId
        this.peerName = fromName ?: fromNodeId
        phase = CallPhase.Incoming
    }

    fun onAccept(callId: String, fromNodeId: String) {
        if (phase == CallPhase.Outgoing && this.callId == callId) {
            activate(fromNodeId)
        }
    }

    fun onReject(callId: String, fromNodeId: String, reason: String?) {
        if (phase == CallPhase.Outgoing && this.callId == callId) {
            notice = when (reason) {
                "занят" -> "Собеседник занят"
                else -> "Звонок отклонён"
            }
            reset()
        }
    }

    fun onHangup(callId: String, fromNodeId: String) {
        if (this.callId == callId && phase != CallPhase.Idle) {
            if (phase == CallPhase.Active) notice = "Звонок завершён"
            reset()
        }
    }

    fun onAudio(callId: String, data: ByteArray) {
        if (phase == CallPhase.Active && this.callId == callId && !muted) {
            val pcm = runCatching { codec.decode(data) }.getOrNull() ?: return
            audio?.playFrame(pcm)
        }
    }

    fun onVideoStart(callId: String, fromNodeId: String, kind: String, width: Int, height: Int) {
        if (phase == CallPhase.Active && this.callId == callId) {
            remoteVideoKind = kind
            remoteVideoFrame = null
            remoteVideoWidth = width
            remoteVideoHeight = height
        }
    }

    fun onVideoFrame(callId: String, kind: String, data: ByteArray) {
        if (phase == CallPhase.Active && this.callId == callId && remoteVideoKind == kind) {
            scope.launch(Dispatchers.Default) {
                val image = VideoCodec.decodeJpeg(data)?.toComposeImageBitmap() ?: return@launch
                if (this@CallManager.callId == callId) {
                    remoteVideoFrame = image
                }
            }
        }
    }

    fun onVideoStop(callId: String, kind: String) {
        if (phase == CallPhase.Active && this.callId == callId && remoteVideoKind == kind) {
            remoteVideoKind = null
            remoteVideoFrame = null
            remoteVideoWidth = 0
            remoteVideoHeight = 0
        }
    }

    // --- внутренности ---

    private fun activate(peerNodeId: String) {
        phase = CallPhase.Active
        muted = false
        notice = null
        startedAt = System.currentTimeMillis()
        seconds = 0
        audio = runCatching { AudioEngine().also { it.open() } }.getOrNull()
        sendJob = scope.launch { audioLoop() }
        tickJob = scope.launch { tickLoop() }
    }

    /** Читает микрофон, кодирует Opus и отправляет кадры собеседнику. */
    private suspend fun audioLoop() {
        while (phase == CallPhase.Active) {
            val engine = audio
            val peer = peerNodeId ?: break
            val id = callId ?: break
            if (engine != null && !muted) {
                val pcm = engine.readMicFrame()
                if (pcm != null) {
                    val packet = runCatching { codec.encode(pcm) }.getOrNull()
                    if (packet != null) {
                        route(peer, CallAudio(id, peer, nodeIdProvider(), seq++, packet))
                    }
                }
            }
            delay(10)
        }
    }

    private suspend fun tickLoop() {
        while (phase == CallPhase.Active) {
            seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            delay(1000)
        }
    }

    /** Запускает трансляцию [kind]: шлёт video.start и цикл захвата кадров. */
    private fun startVideo(kind: String) {
        val source = videoSourceFactory(kind)
        val peer = peerNodeId ?: return
        val id = callId ?: return
        val image = runCatching { source?.capture() }.getOrNull()
        if (source == null || image == null) {
            notice = if (kind == VIDEO_KIND_CAMERA) "Камера недоступна" else "Не удалось захватить экран"
            return
        }
        videoSource = source
        videoKind = kind
        videoSeq = 0
        scope.launch {
            route(peer, VideoStart(id, peer, nodeIdProvider(), kind, image.width, image.height))
        }
        videoJob = scope.launch { videoLoop(kind, source) }
    }

    /** Цикл захвата: кадр → превью → JPEG → отправка (камера ~15 fps, экран ~8 fps). */
    private suspend fun videoLoop(kind: String, source: VideoSource) {
        val interval = if (kind == VIDEO_KIND_SCREEN) 125L else 66L
        while (phase == CallPhase.Active && videoKind == kind) {
            val peer = peerNodeId ?: break
            val id = callId ?: break
            val image = withContext(Dispatchers.IO) { runCatching { source.capture() }.getOrNull() }
            if (image != null) {
                localVideoFrame = image.toComposeImageBitmap()
                val jpeg = withContext(Dispatchers.IO) { VideoCodec.encodeJpeg(image) }
                route(peer, VideoFrame(id, peer, nodeIdProvider(), videoSeq++, kind, jpeg))
            }
            delay(interval)
        }
    }

    private fun reset() {
        phase = CallPhase.Idle
        callId = null
        peerNodeId = null
        peerName = ""
        muted = false
        seconds = 0
        sendJob?.cancel()
        tickJob?.cancel()
        sendJob = null
        tickJob = null
        audio?.close()
        audio = null
        seq = 0
        videoJob?.cancel()
        videoJob = null
        videoSource?.close()
        videoSource = null
        videoKind = null
        remoteVideoKind = null
        remoteVideoFrame = null
        localVideoFrame = null
        remoteVideoWidth = 0
        remoteVideoHeight = 0
        videoSeq = 0
    }
}
