package devchats.server

import devchats.protocol.CallAccept
import devchats.protocol.CallAudio
import devchats.protocol.CallHangup
import devchats.protocol.CallOffer
import devchats.protocol.CallReject
import devchats.protocol.MessageInfo
import devchats.protocol.VideoFrame
import devchats.protocol.VideoStart
import devchats.protocol.VideoStop

/** События узла, на которые подписывается UI (все методы — no-op по умолчанию). */
interface NodeEvents {

    /** Неизвестный узел запросил подключение — нужен accept/deny от владельца. */
    fun onConnectionRequest(peer: Peer) {}

    /** Известный узел подключился к нашему узлу. */
    fun onPeerConnected(peer: Peer) {}

    /** Узел отключился от нашего узла. */
    fun onPeerDisconnected(nodeId: String) {}

    /** Получено новое сообщение (DM или в канале). */
    fun onMessageReceived(message: MessageInfo) {}

    /** Началась входящая передача файла. */
    fun onFileTransfer(record: FileRecord) {}

    /** Обновился прогресс входящей передачи. */
    fun onFileTransferProgress(fileId: String, receivedBytes: Long) {}

    /** Передача завершилась (успешно или с ошибкой). */
    fun onFileTransferFinished(fileId: String) {}

    /** Пришёл вызов, адресованный нашему узлу. */
    fun onIncomingCall(call: CallOffer) {}

    /** Собеседник принял вызов. */
    fun onCallAccepted(call: CallAccept) {}

    /** Собеседник отклонил вызов. */
    fun onCallRejected(call: CallReject) {}

    /** Собеседник завершил звонок. */
    fun onCallHangup(call: CallHangup) {}

    /** Кадр голоса от собеседника. */
    fun onCallAudio(call: CallAudio) {}

    /** Собеседник начал видеотрансляцию (камера или экран). */
    fun onVideoStart(video: VideoStart) {}

    /** Кадр видео от собеседника (JPEG). */
    fun onVideoFrame(video: VideoFrame) {}

    /** Собеседник закончил видеотрансляцию. */
    fun onVideoStop(video: VideoStop) {}
}
